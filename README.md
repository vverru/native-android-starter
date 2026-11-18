# 🛡️ VaultX — Unlimited Cloud Storage (Telegram MTProto Backend)

> **"Unlimited free cloud storage powered by your own Telegram account."**  
> Files are stored securely in a private Telegram channel owned by the **USER**, never on third-party servers. Zero server costs, zero user data collection.

---

## 🌟 Key Features

1. **∞ Unlimited Free Storage**: Files live in your personal private Telegram channel.
2. **Built-in Media Streaming**:
   - 🖼️ **Photo Viewer**: Fullscreen pinch-to-zoom and swipe.
   - 🎬 **Video Player**: ExoPlayer / Media3 streaming with seekbar, buffer indicator & playback speed control (0.75x - 2.0x).
   - 📄 **PDF & Documents**: In-app previews & system `Intent.ACTION_VIEW` for opening in Google Docs / WPS.
3. **⭐ Universal Favorites System**: Star any photo, video, PDF, or document with a dedicated filterable Favorites tab.
4. **🔄 Media Auto-Backup**:
   - DCIM Camera photos & videos auto-sync via WorkManager.
   - WhatsApp Media sync (PRO).
5. **📦 Chunked Uploads for Files > 2GB**: Files larger than Telegram's 2GB free limit are split into 1.5GB chunks with SHA256 integrity metadata.
6. **⏸️ Pause & Resume Uploads**: Persistent floating upload card with real-time transfer speed (`↑ 3.2 MB/s`), progress %, and Pause/Resume/Cancel.
7. **🗑️ Trash Bin**: Soft-delete items to Trash with instant Restore or permanent Delete Forever.
8. **🌐 Bilingual Support**: Full English + हिन्दी (Hindi) native localization.

---

## 🏗️ Architecture

```
User's Phone (VaultX App)
    │
    ├── MTProto login (user's own Telegram account)
    │     └── Session stored ONLY on device
    │
    ├── Private Vault Channel: auto-created on first setup
    │     └── All files sent with metadata (Name, Size, Type, Chunks)
    │
    ├── Local Database: SQLite Room/OpenHelper
    │     └── Stores file index, categories, favorites & trash
    │
    └── Download / Stream: direct streaming from Telegram servers
```

---

## 🚀 How to Download Compiled APK

The app compiles automatically in the cloud via GitHub Actions:

1. Open the [Actions tab](https://github.com/vverru/native-android-starter/actions).
2. Click on the latest workflow run: **Build Android APK**.
3. Under the **Artifacts** section at the bottom, click **`VaultX-Unlimited-Cloud-Storage-APK`** to download the ready-to-install `.apk`!
