# Vendored offline OCR runtime

`Tesseract4Android 4.9.0`, **standard** (single native thread) variant. Built from the official source tag, commit `15c534717b1cb58261b58d4e4c1200c7f81f668c`:
https://github.com/adaptech-cz/Tesseract4Android/tree/15c534717b1cb58261b58d4e4c1200c7f81f668c

AAR SHA-256: `50b85cfb1ee2c378aa59a766f8e6d6028d3462b7b9a3b11b2bf0d55c87641dec`.

The official Maven distribution uses JitPack, which this build environment cannot access. This AAR was built locally from the upstream source, with no code patches. Included components: Tesseract 5.5.1, Leptonica 1.85.0, libjpeg 9f, libpng 1.6.48. Four ABIs: armeabi-v7a, arm64-v8a, x86, x86_64. Upstream enables flexible 16 KB page sizes. No Google Play Services dependency.

Rebuild with JDK 17/21, Android SDK 35, NDK **27.2.12479018**, CMake **3.22.1**, Gradle **8.11.1**, upstream AGP **8.10.1**:

```sh
git clone --branch 4.9.0 --depth 1 https://github.com/adaptech-cz/Tesseract4Android.git
cd Tesseract4Android
./gradlew :tesseract4android:assembleStandardRelease
```

Copy `tesseract4android/build/outputs/aar/tesseract4android-standard-release.aar` here with the versioned filename. For our build, Ninja job pools were limited to six compile jobs and one linker job using CMake arguments; this limits build-host load, not runtime behavior. Recompilation may produce a different archive hash; compare provenance and binaries, not only ZIP bytes.

Language assets: official [tessdata_fast](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd), pinned commit `87416418657359cb625c412a48b6e1d6d41c29bd`:

| File | Bytes | SHA-256 |
|---|---:|---|
| rus.traineddata | 3861738 | e16e5e036cce1d9ec2b00063cf8b54472625b9e14d893a169e2b0dedeb4df225 |
| eng.traineddata | 4113088 | 7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2 |

App verifies the dictionaries when installing them into its private files. They are bundled, so first recognition also works offline. Upstream licenses are included in `app/src/main/assets/licenses`; Apache-2.0 also applies to the language files. Fonts, cloud credentials and build-host signing keys are not part of this artifact.
