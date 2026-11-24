# Model suara untuk wake word "Jarvis"

Dari openWakeWord v0.5.1 (https://github.com/dscripka/openWakeWord/releases/tag/v0.5.1):

- `melspectrogram.onnx` — mengubah audio 16 kHz menjadi spektrogram mel (32 bin).
- `embedding_model.onnx` — model speech embedding umum (dari Google `speech_embedding`),
  mengubah 76 frame mel menjadi vektor 96 angka.

Jarvis tidak memakai model "hey_jarvis" bawaan openWakeWord. Kata panggil dikenali dengan
membandingkan embedding suara yang terdengar dengan contoh rekaman suara pengguna sendiri
(`jarvis/TemplateMatcher.kt`), karena model bahasa Inggris tidak mengenali pelafalan pengguna.
