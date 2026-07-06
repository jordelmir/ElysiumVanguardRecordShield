# Record Shield

**Neo-futuristic Android evidence recording app with cloud backup and stealth mode.**

## Architecture

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                      │
│  HomeScreen → GalleryScreen → PlayerScreen      │
│  SetupScreen → PinScreen → ConsentScreen        │
│  CloudSelectionScreen                           │
├─────────────────────────────────────────────────┤
│                Domain Layer                      │
│  RecordingRepository    ChunkRepository         │
│  EvidenceUploadRepository                       │
├─────────────────────────────────────────────────┤
│              Data Layer                          │
│  SecureStorage (EncryptedSharedPrefs)           │
│  Room DB (Metadata) → Local File System         │
│  CloudStorageManager → GoogleDrive / Supabase   │
├─────────────────────────────────────────────────┤
│             Service Layer                        │
│  RecordingService (Foreground, Stealth)         │
│  UploadWorker (WorkManager, Periodic)           │
│  StealthNotificationManager                     │
└─────────────────────────────────────────────────┘
```

## Security Model

- **PIN**: PBKDF2 (100K iterations) + AndroidKeyStore hardware-backed AES-256
- **Storage**: All recordings in app-private internal storage (invisible to other apps)
- **Encryption**: AES-256-GCM at rest (Android Keystore), TLS 1.3 in transit
- **Integrity**: SHA-256 hash per chunk, tamper detection
- **Stealth**: `IMPORTANCE_MIN` notifications, no visible indicators
- **Consent**: Explicit consent screen before first recording

## Cloud Providers

| Provider | Storage Location | Auth Method |
|----------|-----------------|-------------|
| Google Drive | User's own Drive folder | OAuth2 (drive.file scope) |
| Supabase | Private bucket (RLS) | Device registration token |

## Build

```bash
cd android
./gradlew assembleDebug    # Debug APK
./gradlew assembleRelease  # Release APK
```

## Key Dependencies

- Jetpack Compose (Material3, Material Icons Extended)
- CameraX (video/audio capture)
- Media3 ExoPlayer (playback)
- Room (metadata database)
- Hilt (dependency injection)
- WorkManager (background uploads)
- Ktor CIO (HTTP client)
- EncryptedSharedPreferences (secure storage)
- Play Services Auth (Google Sign-In)
- Google API Client (Drive REST API)

## Play Store Requirements

- [ ] Privacy Policy hosted at URL
- [ ] Google Cloud Console OAuth2 credentials
- [ ] `web_client_id` in `res/values/strings.xml`
- [ ] Signed release APK with keystore
- [ ] Store listing with screenshots
- [ ] Content rating questionnaire
- [ ] Data safety section

## File Structure

```
app/src/main/
├── java/com/elysium/vanguard/recordshield/
│   ├── RecordShieldApplication.kt
│   ├── service/
│   │   ├── RecordingService.kt
│   │   ├── StealthNotificationManager.kt
│   │   └── UploadWorker.kt
│   ├── data/
│   │   ├── local/
│   │   │   ├── Database.kt
│   │   │   ├── SecureStorage.kt
│   │   │   └── PinSecurity.kt
│   │   ├── remote/
│   │   │   ├── EvidenceApiClient.kt
│   │   │   └── DeviceRegistrationClient.kt
│   │   ├── cloud/
│   │   │   ├── CloudStorageProvider.kt
│   │   │   ├── CloudStorageManager.kt
│   │   │   ├── GoogleDriveClient.kt
│   │   │   ├── GoogleDriveStorageProvider.kt
│   │   │   └── SupabaseStorageProvider.kt
│   │   ├── share/
│   │   │   └── SharingManager.kt
│   │   └── repository/
│   │       └── RepositoryImpl.kt
│   ├── ui/
│   │   ├── MainActivity.kt
│   │   ├── MainViewModel.kt
│   │   ├── screen/
│   │   │   ├── home/HomeScreen.kt
│   │   │   ├── gallery/GalleryScreen.kt
│   │   │   ├── player/PlayerScreen.kt
│   │   │   ├── setup/SetupScreen.kt
│   │   │   ├── pin/PinScreen.kt
│   │   │   ├── consent/ConsentScreen.kt
│   │   │   └── cloud/CloudSelectionScreen.kt
│   │   ├── auth/GoogleDriveAuth.kt
│   │   └── theme/Theme.kt
│   ├── di/AppModule.kt
│   └── domain/
│       ├── model/Model.kt
│       └── repository/Repository.kt
├── res/
│   ├── values/strings.xml
│   └── xml/
│       ├── file_paths.xml
│       └── network_security_config.xml
└── proguard-rules.pro
```
