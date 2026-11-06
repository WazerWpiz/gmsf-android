# GMSF Studio

Aplikasi Android untuk membuat musik (Growtopia Music Simulator Final remake) dengan integrasi MCP (Model Context Protocol) untuk komposisi via AI agent.

## Fitur

- **Piano roll 14 nada × 400 kolom** — tap untuk menambah/hapus note
- **15 instrumen** — Piano, Bass, Drum, Sax, Flute, Guitar, Violin, Lyre, Electric, Spooky, Festive, Blank, Audio Gear, Repeat Start/End
- **Playback** — Play/Stop, Play from start, Loop, BPM 20–999
- **Zoom & Scroll** — pinch/zoom, scroll horizontal & vertikal
- **Eraser mode** — hapus note cepat
- **Metadata lagu** — Title, Author, Description
- **Theme** — 6 background preset
- **MCP Server** — HTTP JSON-RPC di port 8765 (27 tools)

## Instalasi

```bash
# Build
cd gmsf-android
./gradlew assembleDebug

# Install ke device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Atau download APK di [Releases](https://github.com/WazerWpiz/gmsf-android/releases).

## MCP (Model Context Protocol)

Server MCP berjalan otomatis saat aplikasi dibuka (foreground service, port **8765**).

**Dari komputer (adb forward):**
```bash
adb forward tcp:8765 tcp:8765
```

**Tools tersedia (27):**
- `get_state`, `get_song`, `set_song`, `set_bpm`, `set_width`
- `add_note`, `add_notes`, `remove_note`, `clear_song`
- `set_instrument`, `set_playing`, `play`, `stop`, `seek`
- `set_volume`, `save_song`, `load_song`, `load_demo`
- `list_songs`, `delete_song`, `export_song`, `import_song`
- `get_instruments`, `set_theme`, `set_repeat`, `describe`

**Contoh compose via agent:**
```bash
curl -X POST http://127.0.0.1:8765/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"add_notes","arguments":{"notes":[{"note":"C4","col":0,"instrument":"piano"},{"note":"E4","col":1,"instrument":"bass"}]}}}'
```

## Export / Import JSON (Folder Downloads)

- **Export** → tombol ↑ (Export JSON) → file `.json` tersimpan di `Downloads/`
- **Import** → tombol ↓ (Import JSON) → pilih file `.json` dari file picker

Format JSON schema `gmsf-song/2` kompatibel dengan GTMUSIC.

## Struktur Project

```
gmsf-android/
├── app/
│   ├── src/main/java/com/mylegguy/gmsf/
│   │   ├── MainActivity.kt      # UI, toolbar, MCP service control
│   │   ├── McpServer.kt         # HTTP JSON-RPC server (27 tools)
│   │   ├── McpService.kt        # Foreground service MCP
│   │   ├── SongBoardView.kt     # Custom view piano roll
│   │   ├── AudioEngine.kt       # SoundPool playback
│   │   ├── Sequencer.kt         # Playback scheduler
│   │   ├── SongRepository.kt    # Save/Load JSON internal
│   │   ├── SongJson.kt          # JSON/GTMUSIC serialize
│   │   ├── Instruments.kt       # 15 instrument families
│   │   └── ...
│   └── src/main/assets/assets/  # Original assets (sound, images, init.lua)
├── build.gradle.kts
└── gradle/
```

## Build Requirements

- Android SDK 34
- JDK 17
- `./gradlew assembleDebug`

## Lisensi

Asset asli dari **Growtopia Music Simulator Final** (MyLegGuy). Kode project ini untuk keperluan edukasi/penelitian.
