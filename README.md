# Nautify 🎵

Pemutar musik Android open source yang memutar lagu dari **YouTube Music**, tanpa iklan , gratis dan tanpa login.
Dibuat dengan Kotlin, Jetpack Compose, Media3 (ExoPlayer), Room, dan NewPipeExtractor.

## Fitur

- **Cari & putar**: cari lagu di YouTube Music (dengan saran pencarian dan infinite scroll), lalu putar di background.
  Ada notifikasi media, kontrol lockscreen, dan kontrol headset/Bluetooth.
- **Antrean**: putar berikutnya, tambah ke antrean, hapus dari antrean, acak, dan ulangi (semua / satu).
- **Radio otomatis**: saat antrean habis, lagu terkait otomatis ditambahkan supaya musik tidak berhenti.
  Hanya lagu yang masuk: podcast, wawancara, siaran langsung, Shorts, dan video lebih dari 12 menit
  disaring keluar (`data/youtube/MusicOnlyFilter.kt`). Pengecualian: DJ remix, mix, nonstop, breakbeat,
  dan sejenisnya boleh sampai 90 menit.
- **Playlist & favorit lokal**: buat, ganti nama, dan hapus playlist; tandai lagu favorit. Riwayat dan
  "paling sering diputar" tersimpan di database lokal (Room).
- **Jarvis (asisten suara)**: ketuk ikon mikrofon di beranda, lalu ucapkan "Jarvis" diikuti perintah.
  - Kata "Jarvis" dideteksi **offline** di HP dengan sherpa-onnx (model keyword spotting ±5 MB di `assets/kws/`).
  - Perintah direkam dengan pengenal suara bawaan Android (bahasa Indonesia), lalu diartikan oleh
    `jarvis/VoiceCommand.kt`: *stop/jeda*, *lanjut/play*, *skip/lagu berikutnya*, *lagu sebelumnya*,
    *volume naik/turun*, *volume 50*, dan *putar <judul/artis>* untuk mencari dan langsung memutar lagu.
  - Jarvis menjawab dengan suara **Kokoro bm_lewis** (pria beraksen Inggris). Modelnya ±300 MB, diunduh sekali
    saat Jarvis pertama dinyalakan; sebelum itu memakai suara bawaan Android.
  - Selama Jarvis aktif ada notifikasi "Jarvis aktif" dengan tombol Matikan.
- **Trending di Indonesia** (beranda): tabel Top 50 Indonesia dari Spotify (peringkat, judul, artis, durasi),
  diperbarui Spotify setiap hari. Setiap lagu dicari otomatis di YouTube Music supaya bisa diputar.
  Tampil 10 teratas, ketuk "Lihat semua" untuk 50 lagu, atau "Putar semua".
- **Impor playlist** (ikon link di tab Pustaka): tempel link playlist atau mix dari YouTube, YouTube Music,
  atau Spotify, lalu playlist itu disalin menjadi playlist lokal.
  - Mix YouTube (`list=RD...`) tidak ada ujungnya, jadi yang diambil hanya 100 lagu pertama.
  - Lagu Spotify dicari satu per satu di YouTube Music (artis + judul, dicocokkan dengan durasi). Hanya playlist
    publik yang bisa dibaca, dan halaman embed Spotify hanya memuat sekitar 100 lagu pertama.
- **Rekomendasi**: beranda menampilkan lagu terkait dari lagu terakhir yang kamu putar.
- **Lirik tersinkron**: lirik dari [LRCLIB](https://lrclib.net) yang bergulir mengikuti lagu.
  Ketuk satu baris untuk lompat ke bagian itu.
- **Musik lokal**:
  - **Pindai HP** menemukan semua lagu yang tersimpan di HP (lewat MediaStore);
  - **Pilih file** menambahkan MP3/M4A/FLAC/OGG/WAV sendiri, lengkap dengan cover bawaan file.
  - Lagu lokal bisa dimasukkan ke playlist, favorit, dan antrean bersama lagu YouTube. Lirik tetap dicari dari LRCLIB.
- **Refresh rate maksimal**: aplikasi meminta layar berjalan di 90/120/144 Hz kalau HP mendukung.
- **Cache audio** 512 MB, jadi lagu yang sering diputar tidak diunduh ulang.
- **UI kaca ala iOS**:
  - dock mengambang (mini player + tab bar) dari kaca buram, dengan kilau pantulan dan tepi berkilau;
  - latar ambient yang warnanya mengikuti cover lagu;
  - layar pemutar berlatar cover yang dibuat buram.
  - Blur asli aktif di Android 12+; versi lama memakai kaca transparan.
- **Efek Audio** (tombol ⚙︎ di layar pemutar):
  - **Kecepatan** 0.5x–2x dan **nada (pitch)** −12 sampai +12 semitone, dengan preset Slowed,
    Slowed + Reverb, Nightcore, dan Sped Up.
  - **Equalizer 5 band** (60 Hz – 14 kHz) dengan preset Bass Boost, Pop, Rock, Dangdut, Vokal, Jazz, dan Treble.
  - **Reverb** (algoritma Freeverb) dengan pengaturan jumlah dan ukuran ruang.
  - **Mode vokal**: Normal, *Instrumental* (vokal dihilangkan), atau *Vokal saja*.
    - Caranya dengan memisahkan bagian tengah stereo, bukan AI, jadi hasilnya tidak sempurna:
      sisa vokal atau musik masih bisa terdengar.
    - Tidak berfungsi pada audio mono.
  - Semua efek diproses sendiri di pipeline ExoPlayer (`audio/EffectsAudioProcessor.kt`), jadi hasilnya
    sama di semua HP dan tidak bergantung pada equalizer bawaan pabrikan.

## Cara kerja singkat

```
UI (Compose) ──► MusicRepository ──► YouTubeMusic (NewPipeExtractor → InnerTube API)
     │                         └──► Room (playlist, favorit, riwayat)
     │                         └──► LrcLib (lirik)
     ▼
PlayerConnection (MediaController) ──► PlaybackService (MediaSession + ExoPlayer)
                                            │
                     nada://song/<videoId>  ▼
                                   StreamResolver ──► URL googlevideo (dicari saat diputar)
                                            │
                                   CacheDataSource ──► OkHttp
```

- Setiap lagu di antrean hanya menyimpan `videoId` (`nada://song/<id>`). URL audio asli baru dicari tepat
  sebelum diputar, karena URL googlevideo kedaluwarsa setelah beberapa jam.
- NewPipeExtractor menangani bagian yang sulit: meniru klien InnerTube, mendekripsi signature dan
  parameter `n`, lalu memilih stream audio.
- Kalau pemutaran gagal karena URL kedaluwarsa (HTTP 403), URL diambil ulang dan lagu dicoba sekali lagi.
- Pencarian memakai filter "Songs" milik YouTube Music. Kalau kosong (terjadi di beberapa wilayah/jaringan),
  otomatis beralih ke filter "Videos".

## Build

Butuh JDK 17+ dan Android SDK (platform 35).

```bash
./gradlew assembleDebug
```

APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

Build release (diperkecil dengan R8, ditandatangani debug key supaya bisa langsung dipasang):

```bash
./gradlew assembleRelease
```

GitHub Actions (`.github/workflows/build.yml`) otomatis membangun APK debug dan release di setiap push ke
`main`. APK bisa diunduh dari tab **Actions**, bagian artefak **nautify-apk**.

## Struktur kode

```
app/src/main/java/com/nada/music/
├── NadaApp.kt                 # inisialisasi NewPipe, OkHttp, database, Coil
├── MainActivity.kt
├── data/
│   ├── Song.kt
│   ├── MusicRepository.kt
│   ├── youtube/               # NewPipeDownloader, YouTubeMusic (search, stream, related)
│   ├── lyrics/LrcLib.kt       # lirik + parser LRC + pembersih judul
│   ├── importer/              # impor playlist YouTube & Spotify
│   └── db/                    # Room: lagu, playlist, relasi
├── audio/                     # pengaturan & DSP: EQ (biquad), reverb (Freeverb), mode vokal
├── playback/                  # PlaybackService, StreamResolver, PlayerCache, PlayerConnection
├── jarvis/                    # asisten suara: wake word, perintah, suara Kokoro
└── ui/                        # Compose: home, search, library, player, effects, glass, theme
```

## Catatan penting

- Aplikasi ini memakai API internal YouTube (bukan API resmi), sama seperti NewPipe, ViMusic, InnerTune,
  dan sejenisnya. Cara ini **melanggar Ketentuan Layanan YouTube**. Gunakan untuk belajar atau pemakaian
  pribadi, dan jangan mengunggahnya ke Play Store.
- YouTube rutin mengubah sistemnya. Kalau pencarian atau pemutaran tiba-tiba gagal, biasanya cukup
  memperbarui versi `NewPipeExtractor` di `app/build.gradle.kts` ke rilis terbaru
  (<https://github.com/TeamNewPipe/NewPipeExtractor/releases>).
- Terlalu banyak request dari satu IP (misalnya VPN atau server) bisa membuat YouTube meminta captcha atau
  membalas 403. Biasanya ini hilang sendiri setelah beberapa saat.
- Sebelum membagikan APK release, ganti `signingConfig` di `app/build.gradle.kts` dengan keystore milikmu.
  Kalau tidak, versi yang di-build dari komputer lain tidak bisa dipasang sebagai update di atas versi lama.
- Proyek ini butuh Gradle JDK 17 atau 21. Gradle 8.11 tidak bisa berjalan dengan JDK 25.

## Lisensi

GPL-3.0, mengikuti lisensi NewPipeExtractor.
