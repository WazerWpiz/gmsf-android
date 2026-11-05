# GMSF Modern — Kotlin Android Scaffolding

Rebuild modern dari hasil inspeksi `base.apk` (`com.mylegguy.gmsf` / "Growtopia Music Simulator Final") yang sebelumnya dianalisis dengan **Ghidra headless** (`libmain.so`, AARCH64) dan **apktool**.

Saat ini saya membuat **scaffolding Kotlin Android** (bukan Node.js/web), karena aplikasi asli adalah Android APK. Fungsi yang ditemukan lewat inspeksi dan direplikasi di sini:

| Fungsi native (Ghidra) | Implementasi Kotlin |
|---|---|
| `Java_org_libsdl_app_SDLActivity_nativeInit` | `MainActivity.onCreate` + `AudioEngine.init` |
| `controlLoop` | `Sequencer` (loop 16 langkah, 16th-note grid) |
| `pushLuaFunctions` / `L_setBPM` / `L_getBPM` | `Sequencer.setBpm` / UI BPM input |
| `loadGMSASong` / `saveSong` | `SongRepository.save/load/list/delete` (JSON ke penyimpanan eksternal) |
| `L_loadSound` / `play_note` | `AudioEngine.playNote` (Web Audio-like synthesizer via `AudioTrack`) |
| `L_addNote` / `uiAddUI` | sequencer add/remove note (`toggleCell`) |
| `L_loadTheme` / `uiTheme` | `Theme.Gmsf` (DayNight, gelap/terang) |
| `fixSafeDataPath` | `SongRepository.safeName` (sanitasi nama file) |

## Struktur project

```
gmfs-android/
├── settings.gradle.kts
├── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   ├── src/main/AndroidManifest.xml
│   ├── src/main/java/com/mylegguy/gmsf/
│   │   ├── MainActivity.kt
│   │   ├── AudioEngine.kt
│   │   ├── Sequencer.kt
│   │   ├── Song.kt
│   │   └── SongRepository.kt
│   └── src/main/res/
│       ├── layout/activity_main.xml
│       ├── values/strings.xml
│       ├── values/colors.xml
│       └── values/themes.xml
```

## Build & Jalankan

Butuh **Android Studio / Android SDK** dengan `compileSdk 34`, `buildTools`, dan `ndk` tidak diperlukan (sampu kode Kotlin murni).

```bash
cd /root/mcp/new/gmsf-android
# buka di Android Studio, atau
./gradlew assembleDebug
```

## Catatan MCP

Sebelumnya saya juga menyediakan **server MCP Node.js** (`/root/mcp/new/server.js`) yang mengekspos alat musik yang sama melalui stdio (JSON-RPC 2.0): `get_state`, `set_bpm`, `play_note`, `save_song`, `load_song`, `list_songs`, dll. Kotlin Android di atas adalah versi native APK dari aplikasi yang sama; jika ingin satu aplikasi tunggal dengan MCP di Android, bisa menambahkan `ServerSocket` atau `MCP-over-WebSocket` di `MainActivity` yang memanggil `AudioEngine`/`Sequencer`/`SongRepository`.

## Fitur UI

- Piano keyboard (klik untuk bunyi)
- Sequencer 16 langkah × nada (C3..C5)
- Putar / Berhenti, BPM, nama lagu
- Simpan / Muat lagu (JSON)
- Tema gelap/terang (Material DayNight)
