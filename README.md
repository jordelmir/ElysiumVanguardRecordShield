# 🛡️ Elysium Vanguard Record Shield

> **Anti-sabotage emergency recording system with real-time cloud evidence preservation.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?logo=android)](https://developer.android.com/jetpack/compose)
[![Vercel](https://img.shields.io/badge/Vercel-Edge-000000?logo=vercel)](https://vercel.com)
[![Supabase](https://img.shields.io/badge/Supabase-PostgreSQL-3FCF8E?logo=supabase)](https://supabase.com)

---

## 🎯 What Is This?

Record Shield captures audio and video evidence and uploads it to the cloud **in real-time fragments (every 5-10 seconds)**. If the recording device is destroyed, confiscated, or powered off, all previously uploaded fragments survive safely in Supabase Storage.

### Key Features

- 🔴 **1-Click Recording** — Massive button for instant capture
- 🔒 **Anti-Sabotage Lock** — PIN-protected; back button disabled during recording
- ☁️ **Real-Time Chunking** — Evidence uploaded every 5-10 seconds
- 📱 **Screen-Off Recording** — Foreground Service + WakeLock persistence
- 🗂️ **Internal Sandbox** — Zero external storage; invisible to galleries/USB
- 🎬 **Secure Gallery** — PIN-gated playback with full-screen stretched video
- 🎨 **Neo-Futuristic UI** — Glassmorphism, Matrix Rain, animated glow effects

---

## 🏗 Architecture

```
Android (Kotlin/Compose)  →  Vercel Edge (TypeScript)  →  Supabase (PostgreSQL + Storage)
     CameraX + Media3            /api/upload-evidence          evidence-vault bucket
```

See [`skills.md`](./skills.md) for the complete data flow documentation.

---

## 📂 Project Structure

```
ElysiumVanguardRecordShield/
├── android/                  # Native Android app (Kotlin/Compose)
│   ├── app/
│   │   ├── build.gradle.kts  # Dependencies: CameraX, Media3, Ktor, Hilt, Room
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       └── java/com/elysium/vanguard/recordshield/
│   ├── build.gradle.kts      # Project-level (AGP 8.7, Kotlin 2.1, KSP)
│   └── settings.gradle.kts
├── vercel/                   # Serverless API
│   ├── api/
│   │   └── upload-evidence.ts    # Chunk upload endpoint
│   ├── package.json
│   ├── vercel.json
│   └── tsconfig.json
├── supabase/
│   └── migrations/
│       └── 001_init_evidence_schema.sql  # Tables + RLS + Storage bucket
├── skills.md                 # Data flow documentation
└── README.md                 # This file
```

---

## 🚀 Quick Start

### 1. Supabase Setup

```bash
# Apply the migration to your Supabase project
# Option A: Via Supabase CLI
supabase db push

# Option B: Copy/paste the SQL from:
# supabase/migrations/001_init_evidence_schema.sql
# into the Supabase Dashboard → SQL Editor
```

### 2. Vercel Deployment

```bash
cd vercel/
npm install

# Set environment variables
# In Vercel Dashboard → Settings → Environment Variables:
#   SUPABASE_URL = https://rohninosfwpmjmcklhbh.supabase.co
#   SUPABASE_SERVICE_ROLE_KEY = (from Supabase Dashboard → API)

# Deploy
npx vercel --prod
```

### 3. Android

Open `android/` in Android Studio. Sync Gradle. Build and run.

Update the Vercel API URL in `app/build.gradle.kts`:

```kotlin
buildConfigField("String", "VERCEL_API_URL", "\"https://your-project.vercel.app\"")
```

---

## 🔐 Security Model

| Layer | Protection |
|-------|-----------|
| **Device → Vercel** | HTTPS + Device Token + SHA-256 integrity |
| **Vercel → Supabase** | Service Role Key (server-side only) |
| **Supabase Storage** | Private bucket, RLS-protected |
| **Local Storage** | App sandbox (invisible to OS) |
| **App Access** | PIN/password lock |
| **Evidence Immutability** | No UPDATE/DELETE policies in RLS |

---

## 📋 Development Phases

- [x] **Phase 1:** Infrastructure (Supabase + Vercel + Android scaffold)
- [ ] **Phase 2:** Core Services (Recording, Chunking, Uploading)
- [ ] **Phase 3:** Security (PIN lock, anti-sabotage mechanisms)
- [ ] **Phase 4:** UI/UX (Neo-futuristic design system)
- [ ] **Phase 5:** Integration (Widget, optimization, full docs)

---

## 📄 License

Proprietary — All rights reserved.

---

*Built with the highest engineering standards. Zero shortcuts. Zero garbage code.*
