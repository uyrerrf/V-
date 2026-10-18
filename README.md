<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:7C3AED,50:A855F7,100:6366F1&height=220&section=header&text=FasonRat&fontSize=70&fontColor=ffffff&animation=fadeIn&fontAlignY=32&desc=Advanced%20Android%20Remote%20Administration%20Platform&descSize=18&descAlignY=52&descColor=e0e7ff" width="100%">
</p>

<p align="center">
  <img src=".github/assets/logo/logo.svg" alt="FasonRat Logo" width="120">
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Version-3.1.0-7C3AED?style=flat-square&logo=semver&logoColor=white" alt="Version 3.1.0">
  <img src="https://img.shields.io/badge/Android-7--16+-34D399?style=flat-square&logo=android&logoColor=white" alt="Android 7-16+">
  <img src="https://img.shields.io/badge/Node.js-22+-339933?style=flat-square&logo=node.js&logoColor=white" alt="Node.js 22+">
  <img src="https://img.shields.io/badge/Fastify-5-20223A?style=flat-square&logo=fastify&logoColor=white" alt="Fastify 5">
  <img src="https://img.shields.io/badge/Java-17-F89820?style=flat-square&logo=openjdk&logoColor=white" alt="Java 17">
  <img src="https://img.shields.io/badge/React-19-61DAFB?style=flat-square&logo=react&logoColor=black" alt="React 19">
  <img src="https://img.shields.io/badge/License-MIT-3B82F6?style=flat-square&logo=opensourceinitiative&logoColor=white" alt="MIT License">
  <a href="https://t.me/fasonrat"><img src="https://img.shields.io/badge/Telegram-Join%20Channel-26A5E4?style=flat-square&logo=telegram&logoColor=white" alt="Telegram"></a>
</p>

<p align="center">
  <img src="https://readme-typing-svg.demolab.com?font=Inter&weight=600&size=22&duration=3000&pause=1000&color=A855F7&center=true&vCenter=true&multiline=true&repeat=true&width=600&height=80&lines=Full-featured+self-hosted+remote+management;suite+for+Android+devices" alt="Typing SVG" />
</p>

---

## 🩷 Support the project

If you enjoy this project, consider supporting its development. Every contribution helps me bring new updates and improvements.
<div align="center">
<img src="https://img.shields.io/badge/BNB%20Smart%20Chain-F3BA2F?style=for-the-badge&logo=binance&logoColor=black" alt="BNB Smart Chain">
<img src="https://img.shields.io/badge/BEP20-0F9D58?style=for-the-badge" alt="BEP20">

**BNB Smart Chain (BEP20)**

Send **BNB** or **any BEP20 token** (such as **USDT**, **USDC**, **BTCB**, and more) to the address below:

</div>

```text
0xdde4c67f0bcb0fb0c40f6e9ccd1b8070d545129d
```

> ⚠️ **Please use the BNB Smart Chain (BEP20) network when sending funds.**
> 

---

## ✨ Features

### 📱 Device Management
- **Device Info** — Model, brand, Android version, battery, memory, storage, network, screen
- **Real-time Connection** — Socket.IO based live communication with auto-reconnect
- **Multi-device Support** — Manage unlimited devices from single dashboard
- **IP Geolocation** — Country and city detection for connected devices
- **Device Authentication** — Each device authenticates with a secret generated during setup; regenerable from Settings

### 📡 Remote Access
- 📱 **SMS** — Read inbox/sent messages, send SMS, view conversations, real-time incoming SMS push via SmsReceiver
- 📞 **Call Logs** — View call history with timestamps and duration
- 👥 **Contacts** — Access device contacts
- 📍 **GPS Location** — Real-time tracking with configurable polling interval
- 📂 **File Manager** — Browse storage, download files (chunked transfer), upload files, push files to device, delete, rename, encrypt/decrypt
- 📷 **Camera** — Capture photos, record video, and live stream from front/back camera with target FPS and quality control
- 🎤 **Microphone** — Record audio remotely with custom duration and live PCM streaming with real-time rate stats
- 📋 **Clipboard** — Monitor clipboard content in real-time
- 🔔 **Notifications** — Capture and view all device notifications via NotificationListenerService
- 📶 **WiFi Scanner** — Scan nearby WiFi networks with signal details
- 📦 **Installed Apps** — List all installed applications with package info
- 🔐 **Permissions** — View granted/denied permissions, prompt for missing ones
- 📥 **Downloads** — Centralized download manager for all transferred files

### 🖥️ Screen & Control
- 🖥️ **HVNC** — Hidden VNC: view and control device screen remotely via MediaProjection with mouse input injection
- 🔍 **Inspector** — Accessibility tree inspector for live UI element introspection and interaction
- ⌨️ **Keylogger** — Capture keystrokes with package attribution and password detection
- 🔒 **Unlock** — Remote lock/unlock device screen with PIN injection via AccessibilityService
- 👻 **Fason** — Toggle app icon visibility from device launcher (hide/show mode)

### ⚡ Background Resilience
- 🔄 **Auto Boot** — Starts automatically on device boot
- 🛡️ **Watchdog** — Keeps service alive via TIME_TICK monitoring
- 📡 **Auto Reconnect** — Reconnects on network change with backoff
- 🔋 **Wake Lock** — Ensures reliable background operation
- ⏰ **WorkManager** — KeepAliveWorker periodic job as fallback
- 👻 **Stealth Notification** — Minimal foreground notification with VISIBILITY_SECRET

### 🛠️ APK Builder
- Customize server URL, app name, icon, and home page URL
- Auto-signed APK via uber-apk-signer

### 🖥️ Web Dashboard
- **Dashboard** — Real-time stats, online/offline counts, system uptime, memory usage
- **Devices** — Browse all connected devices with search and filter
- **Builder** — Build customized APK from the web interface
- **Users** — Role-based access (admin/user) with granular permissions
- **Settings** — Manage profile, change password, regenerate device secret
- **Logs** — System event log with filtering, search, and clear
- **Sessions** — View and revoke active login sessions
- **Setup Wizard** — First-boot guided setup at `/setup` (creates admin account + device secret)
- **Dark/Light Theme** — System-aware toggle with persistent preference

### 🔐 Security
- JWT session auth with HTTP-only cookies, 24-hour expiry
- Per-IP rate limiting (default: 100 req/min)
- IP-based login lockout after 5 failed attempts
- Role-based access control with 29 granular permissions
- Device-to-server authentication via secret token
- Session management — view and revoke active sessions
- Global error handler prevents stack trace leaks in production
- Network security config — cleartext traffic blocked by default
- `autoRevokePermissions` disallowed to prevent Android from revoking runtime permissions

---

<details>
<summary>🖥️ Screenshots</summary>

| Login | Dashboard |
|:-----:|:---------:|
| <img src=".github/assets/screenshot/login.png" width="400"> | <img src=".github/assets/screenshot/dashboard.png" width="400"> |

| Devices | Users |
|:-------:|:-----:|
| <img src=".github/assets/screenshot/devices.png" width="400"> | <img src=".github/assets/screenshot/users.png" width="400"> |

| APK Builder | Settings |
|:-----------:|:--------:|
| <img src=".github/assets/screenshot/builder.png" width="400"> | <img src=".github/assets/screenshot/settings.png" width="400"> |

| Activity Logs | |
|:-------------:|:---:|
| <img src=".github/assets/screenshot/logs.png" width="400"> | |

</details>

---

<details>
<summary>🚀 Getting Started</summary>

### Requirements

| Component | Requirement |
|-----------|-------------|
| **Server** | Node.js 22+, npm, Java 17 JRE (for APK builder) |
| **Android** | minSdk 24 (Android 7.0), targetSdk 35 (Android 15), Java 17 |

### Docker (Recommended)
```bash
docker run -d \
  --name fasonrat \
  -p 32766:32766 \
  -v fasonrat-data:/app/backend/data \
  fahimahamed/fasonrat:latest
```

Or with Docker Compose:
```bash
git clone https://github.com/fahimahamed1/FasonRat.git
cd FasonRat
docker compose -f docker/docker-compose.yml up -d
```

### CLI Start

```bash
git clone https://github.com/fahimahamed1/FasonRat.git
cd FasonRat
npm install
npm run build
npm start
```

Access dashboard at `http://localhost:32766`

> 🔐 First boot opens the **Setup Wizard** at `/setup` — create the admin account and generate the device secret.

### Development

```bash
npm run dev:backend    # Hot-reload via tsx watch
npm run dev:frontend   # Vite dev server with HMR
npm run dev            # Start both concurrently
```

### Build APK

**Option A: Web Builder (Recommended)**
1. Open dashboard → **Builder**
2. Enter server URL (e.g., `http://192.168.1.100:32766`)
3. Set custom app name, icon, and home page URL
4. Click **Build APK** → Download signed `Fason.apk`

**Option B: Gradle**
```bash
cd fason
./gradlew assembleDebug    # Debug build (~6.4 MB, ARM-only)
./gradlew assembleRelease  # Release build (requires keystore env vars)
```

</details>

---

<details>
<summary>🏗️ Architecture</summary>

```
FasonRat/
├── fason/              Android client (Java 17, Socket.IO)
├── backend/            Node.js server (Fastify 5, SQLite, Socket.IO)
├── frontend/           React dashboard (React 19, Vite 8, Tailwind CSS 4)
├── docker/             Dockerfile + docker-compose.yml + docker-compose.local.yml
└── .github/workflows/  CI/CD (Docker push, APK build, Cloudflare test server)
```

| Layer | Stack | Port |
|-------|-------|------|
| **Android** | Java 17, SDK 35, Socket.IO, CameraX, WorkManager | — |
| **Backend** | Node.js 22, Fastify 5, SQLite, Drizzle ORM | 32766 |
| **Frontend** | React 19, Vite 8, Tailwind CSS 4, shadcn/ui, Zustand | 5173 (dev) |

</details>

---

<details>
<summary>⚙️ Configuration</summary>

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `32766` | Server listen port |
| `NODE_ENV` | `production` | Node.js environment |
| `BETTER_AUTH_SECRET` | auto-generated | Auth + JWT signing secret (min 32 chars) |
| `BETTER_AUTH_URL` | — | Public HTTPS URL of the deployment |
| `BETTER_AUTH_TRUSTED_ORIGINS` | — | Comma-separated list of trusted origins |
| `FASON_TRUST_PROXY` | `0` | Set to `1` when behind nginx/Caddy/Cloudflare |
| `LOG_LEVEL` | `info` | Logging level |

### Health Check

`GET /api/health` returns `{"status":"ok"}` — used by Docker `HEALTHCHECK` and reverse proxies.

### Database

SQLite via `better-sqlite3` with Drizzle ORM. Auto-created at `backend/data/fasonrat.db` on first run.

```bash
npm run db:generate   # Generate migration SQL
npm run db:migrate    # Run migrations
npm run db:push       # Push schema directly
npm run db:studio     # Open Drizzle Studio
```

</details>

---

<details>
<summary>🤖 CI/CD Workflows</summary>

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| **Build & Push Docker Image** | Manual (`workflow_dispatch`) | Builds multi-arch Docker image, optionally pushes to Docker Hub, syncs README to Hub |
| **Build Base APK** | Manual (`workflow_dispatch`) | Builds debug/release APK via Gradle, uploads as artifact, optionally commits to repo |
| **Run Server (Cloudflare)** | Manual (`workflow_dispatch`) | Spins up the server on a GitHub runner with a Cloudflare tunnel for manual testing |

All workflows live in `.github/workflows/` and run on `ubuntu-latest`.

</details>

---

<details>
<summary>📦 Docker</summary>

**Pre-built image** (Docker Hub):
```bash
docker run -d \
  --name fasonrat \
  -p 32766:32766 \
  -v fasonrat-data:/app/backend/data \
  fahimahamed/fasonrat:latest
```

**Compose (pre-built image):**
```bash
docker compose -f docker/docker-compose.yml up -d
```

**Compose (local build):**
```bash
docker compose -f docker/docker-compose.local.yml up -d --build
```

The image bundles Node.js 22 + OpenJDK 17 JRE (for the in-container APK builder), runs as a non-root `app` user, and ships with a `tini` init + health check.

</details>

---

<details>
<summary>🛡️ Security Notes</summary>

⚠️ **This tool is intended for:**
- Personal device management
- Parental control (with consent)
- Enterprise device management
- Educational and research purposes

**Do NOT use for:**
- Unauthorized device access
- Surveillance without consent
- Any illegal activities

> Always ensure proper authorization before installing on any device.

</details>

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

## 👨‍💻 Author

**Fahim Ahamed**

[![GitHub](https://img.shields.io/badge/GitHub-fahimahamed1-181717?style=flat-square&logo=github)](https://github.com/fahimahamed1)

---

## ⭐ Support

If you find this project useful, please consider giving it a star! 🌟 Contributions, issues, and ideas are welcome.

## 💬 Community

Join the FasonRat community on Telegram for discussions, updates, and support:

[![Telegram](https://img.shields.io/badge/Telegram-Join%20Channel-26A5E4?style=flat-square&logo=telegram&logoColor=white)](https://t.me/fasonrat)

---

<p align="center">
  <img src="https://capsule-render.vercel.app/api?type=waving&color=0:7C3AED,50:A855F7,100:6366F1&height=120&section=footer" width="100%">
</p>
