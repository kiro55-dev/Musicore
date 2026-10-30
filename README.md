# Musicore – reproductor local para Android (núcleo en Rust)

## Arquitectura
- **rust-core/** (Rust): listas, carpetas asociadas, cola aleatoria (pool + queue), persistencia JSON y borrado lógico. Expuesto por JNI con una sola función `nativeCall(método, json)`.
- **app/** (Kotlin + Compose + Media3): audio (ExoPlayer), MediaSession (botones de auriculares/Bluetooth/pantalla de bloqueo), escaneo de carpetas con SAF, UI.

## Compilar
1. Android Studio (Ladybug o superior) con SDK 35 y **NDK** (SDK Manager → SDK Tools → NDK).
2. Rust: `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`
3. Abre la carpeta en Android Studio (genera el Gradle wrapper) y pulsa Run.
   Gradle compila el crate con el plugin `rust-android-gradle`.
4. Tests del núcleo: `cd rust-core && cargo test`
