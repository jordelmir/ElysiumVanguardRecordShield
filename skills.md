# 🛡️ Elysium Vanguard Record Shield — Data Flow & Architecture Skills

> **Classification:** Internal Technical Documentation  
> **Version:** 1.0.0-alpha  
> **Last Updated:** 2026-03-08

---

## 1. System Overview

Elysium Vanguard Record Shield is an anti-sabotage evidence recording system designed to ensure that captured audio/video evidence survives even if the recording device is physically destroyed, confiscated, or forcibly powered off.

**The core principle:** Every second of captured evidence is fragmented and transmitted to cloud storage in near-real-time. If an adversary destroys the phone 30 seconds into a recording, the first 25-30 seconds are already safely persisted in Supabase Storage.

---

## 2. Complete Data Flow: Camera → Cloud

```
┌─────────────────────────────────────────────────────────────────────┐
│                        ANDROID DEVICE                               │
│                                                                     │
│  ┌──────────┐    ┌──────────────┐    ┌──────────────────────┐      │
│  │ CameraX  │───▶│ MediaCodec   │───▶│ FileOutputProvider   │      │
│  │ Pipeline  │    │ (H.264/AAC) │    │ Context.getFilesDir()│      │
│  └──────────┘    └──────────────┘    └──────────┬───────────┘      │
│                                                  │                  │
│                                    ┌─────────────▼──────────────┐  │
│                                    │     ChunkSplitter          │  │
│                                    │  (Every 5-10 seconds)      │  │
│                                    │  • SHA-256 hash each chunk │  │
│                                    │  • Save to internal DB     │  │
│                                    └─────────────┬──────────────┘  │
│                                                  │                  │
│                                    ┌─────────────▼──────────────┐  │
│                                    │     UploadWorker            │  │
│                                    │  (Ktor + WorkManager)      │  │
│                                    │  • Exponential backoff     │  │
│                                    │  • Survives app restart    │  │
│                                    └─────────────┬──────────────┘  │
└──────────────────────────────────────────────────┼──────────────────┘
                                                   │
                              HTTPS POST           │
                        X-Device-Token             │
                        X-Chunk-Hash (SHA-256)     │
                                                   ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       VERCEL EDGE NETWORK                           │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  POST /api/upload-evidence                                   │   │
│  │                                                              │   │
│  │  1. Validate X-Device-Token (bcrypt against DB)              │   │
│  │  2. Verify SHA-256 integrity of chunk binary                 │   │
│  │  3. Check recording exists & belongs to device               │   │
│  │  4. Upload binary to Supabase Storage                        │   │
│  │  5. Register metadata via register_chunk() SQL function      │   │
│  │  6. Return 201 with chunk confirmation                       │   │
│  └────────────────────────────────┬─────────────────────────────┘   │
└───────────────────────────────────┼─────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         SUPABASE                                    │
│               https://rohninosfwpmjmcklhbh.supabase.co             │
│                                                                     │
│  ┌──────────────────┐    ┌──────────────────────────────────────┐  │
│  │  PostgreSQL DB    │    │  Storage: evidence-vault (Private)   │  │
│  │                   │    │                                      │  │
│  │  • devices        │    │  Path: {device_id}/                  │  │
│  │  • recordings     │    │         {recording_id}/              │  │
│  │  • evidence_chunks│    │           chunk_00000.mp4            │  │
│  │                   │    │           chunk_00001.mp4            │  │
│  │  RLS: device-     │    │           chunk_00002.mp4            │  │
│  │  scoped isolation │    │           ...                        │  │
│  └──────────────────┘    └──────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Data Flow Stages (Detailed)

### Stage 1: Capture (Android — CameraX Pipeline)

| Component | Role |
|-----------|------|
| `CameraX.VideoCapture` | Binds to the camera lifecycle, produces a continuous H.264/AAC stream |
| `MediaCodec` | Hardware-accelerated encoding (automatically selected by CameraX) |
| `FileOutputProvider` | Writes output to `Context.getFilesDir()` — the app's **private sandbox** |

**Why CameraX over Camera2:** CameraX auto-negotiates resolution, frame rate, and encoding profiles per device. Camera2 requires 3x more code for the same result and doesn't handle device-specific quirks.

**Why internal storage:** Files in `getFilesDir()` are:

- Invisible to the system gallery (MediaStore)
- Inaccessible via USB/MTP (even with USB debugging on)
- Encrypted at rest on devices with FBE (Android 7+)
- Automatically deleted when the app is uninstalled

### Stage 2: Chunk Splitting (Android — ChunkSplitter)

The recording stream is split into discrete chunks every **5-10 seconds**. This is implemented using CameraX's `OutputOptions` with a file size or duration limit, combined with a coroutine-based splitter:

```kotlin
// Pseudocode — actual implementation in Phase 2
while (isRecording) {
    val chunkFile = createChunkFile(chunkIndex)
    cameraVideoCapture.output(chunkFile, maxDuration = 10.seconds)
    val hash = chunkFile.sha256()
    localDb.insertChunk(recordingId, chunkIndex, chunkFile.path, hash)
    uploadQueue.enqueue(chunkFile, hash, chunkIndex)
    chunkIndex++
}
```

**Why 5-10 seconds:** This is the optimal balance between:

- **Survival granularity:** Maximum 10 seconds of evidence lost if device is destroyed
- **Upload efficiency:** Chunks are large enough to amortize HTTP overhead
- **Network resilience:** Small enough to upload over poor connections

### Stage 3: Upload (Android → Vercel)

| Component | Role |
|-----------|------|
| `Ktor HttpClient` | Sends raw binary POST with authentication headers |
| `WorkManager` | Guarantees delivery with exponential backoff and reboot survival |

**HTTP Request Structure:**

```
POST /api/upload-evidence HTTP/1.1
Host: your-project.vercel.app
Content-Type: video/mp4
X-Device-Token: <rotatable-api-key>
X-Device-Id: <device-uuid>
X-Recording-Id: <recording-uuid>
X-Chunk-Index: 0
X-Chunk-Hash: <sha256-hex>

[binary chunk data]
```

**Why Ktor over Retrofit:** Ktor handles streaming binary uploads natively without the overhead of Retrofit's multipart abstraction. For raw binary payloads, Ktor's `ByteReadChannel` is more memory-efficient.

**Why WorkManager:** Unlike coroutine-only uploads, WorkManager:

- Survives process death
- Retries after device reboot
- Respects battery and network constraints
- Uses exponential backoff automatically

### Stage 4: Validation & Storage (Vercel Edge)

The Vercel endpoint (`/api/upload-evidence`) performs these operations in sequence:

1. **Token Validation:** Queries Supabase `devices` table to verify the device exists and is active. Verifies the token against the stored bcrypt hash using pgcrypto.

2. **Integrity Check:** Recomputes SHA-256 of the received binary and compares it to the `X-Chunk-Hash` header. Rejects mismatched chunks (network corruption or tampering).

3. **Recording Ownership:** Verifies the `X-Recording-Id` exists and belongs to the `X-Device-Id`.

4. **Storage Upload:** Streams the chunk binary to `evidence-vault/{device_id}/{recording_id}/chunk_{index}.mp4` using the Supabase service_role key.

5. **Metadata Registration:** Calls `register_chunk()` SQL function which atomically inserts the chunk record AND updates the recording's aggregate counters.

### Stage 5: Persistence (Supabase)

Evidence is stored in two places for redundancy:

| Storage Layer | Content | Purpose |
|---------------|---------|---------|
| **Supabase Storage** (`evidence-vault`) | Binary audio/video chunks | Actual evidence files |
| **PostgreSQL** (`evidence_chunks`) | Metadata: path, hash, size, timestamp | Indexing, integrity verification, reconstruction |

**RLS (Row Level Security):**

- `service_role` key (Vercel only) can write to all tables and storage
- Device-scoped JWTs can only SELECT their own data
- **No device can UPDATE or DELETE evidence** — immutability is enforced at the database level

---

## 4. Security Architecture

### 4.1 Authentication Flow

```
┌──────────┐     ┌──────────────┐     ┌──────────────┐
│  Device   │────▶│  Vercel API  │────▶│  Supabase DB │
│           │     │              │     │              │
│ API Key   │     │ Validate     │     │ bcrypt hash  │
│ (cleartext│     │ against DB   │     │ comparison   │
│  in header│     │              │     │              │
└──────────┘     └──────────────┘     └──────────────┘
       ▲                                     │
       │            ✅ Validated              │
       └──────────────────────────────────────┘
```

### 4.2 Anti-Sabotage Mechanisms

| Threat | Countermeasure |
|--------|---------------|
| Device destroyed mid-recording | Chunks uploaded every 5-10s; evidence survives |
| App force-closed | Foreground Service + WakeLock; Android restarts the service |
| Screen locked/turned off | Recording continues via Foreground Service |
| Adversary tries to stop recording | UI locked; requires PIN to stop. Back button disabled. |
| Phone taken and app opened | PIN/password required to access any screen |
| Evidence deletion attempt | Files in app sandbox; no file manager access. Cloud copies immutable. |
| Man-in-the-middle on upload | HTTPS + SHA-256 integrity verification |
| Token theft / replay attack | API keys are rotatable; bcrypt-hashed in DB |

### 4.3 Storage Sandbox Model

```
/data/data/com.elysium.vanguard.recordshield/files/
├── recordings/
│   ├── {recording-uuid-1}/
│   │   ├── chunk_00000.mp4    ← Invisible to gallery
│   │   ├── chunk_00001.mp4    ← Invisible to USB/MTP
│   │   └── chunk_00002.mp4    ← Encrypted at rest (FBE)
│   └── {recording-uuid-2}/
│       └── ...
└── metadata.db                ← Room SQLite database
```

---

## 5. Technology Stack Justification

| Layer | Technology | Why This Choice |
|-------|-----------|-----------------|
| **Capture** | CameraX 1.4+ | Lifecycle-aware, auto-quirk handling, built-in video capture |
| **Playback** | Media3 1.5+ | Official ExoPlayer successor, RESIZE_MODE_FILL support |
| **UI** | Compose + Material3 | Declarative, animation-first, neo-futuristic design |
| **Networking** | Ktor 3.0+ | Kotlin-native, streaming binary uploads, coroutine-first |
| **Local DB** | Room | Compile-time SQL verification, Flow integration |
| **Background** | WorkManager | Guaranteed delivery, reboot survival, backoff |
| **DI** | Hilt | Compile-time verification, lifecycle scoping |
| **Security** | Security-Crypto | AES-256-GCM via Android Keystore |
| **Middleware** | Vercel Edge | Global CDN, serverless, <100ms cold start |
| **Database** | Supabase PostgreSQL | RLS, pgcrypto, real-time subscriptions |
| **Storage** | Supabase Storage | S3-compatible, RLS-protected, CDN delivery |

---

## 6. Error Recovery Matrix

| Failure Scenario | System Response |
|-----------------|-----------------|
| Network loss during upload | WorkManager queues chunk; retries with exponential backoff |
| Vercel function timeout | Client retries; chunk deduplication prevents double-writes |
| Supabase Storage down | Chunk kept in local sandbox; WorkManager retries indefinitely |
| Duplicate chunk uploaded | `UNIQUE(recording_id, chunk_index)` constraint returns 409 |
| Chunk corrupted in transit | SHA-256 mismatch → 422 error → client re-uploads original |
| App killed by OOM | Foreground Service restarted by Android; recording resumes |
| Device rebooted | Boot receiver triggers WorkManager to upload pending chunks |

---

*This document serves as the single source of truth for the Elysium Vanguard Record Shield data pipeline. All architectural decisions are documented with their rationale to enable knowledge transfer at scale.*
