# Troubleshooting

## Building

| Symptom | Fix |
|---|---|
| `./gradlew` cannot resolve Android plugins | The build needs `google()` and `mavenCentral()`; run `./gradlew --no-daemon assembleDebug` from the repository root |
| Local build without Gradle | `tools/setup_toolchain.sh` reconstructs a Kotlin 1.9.23 + JDK 17 + `android.jar` toolchain, then `tools/localtest.sh` compiles and runs the headless suite |
| `ClassNotFoundException: kotlin.jvm.internal.Intrinsics` | Run tests through `tools/localtest.sh` — it puts the Kotlin stdlib on the runtime classpath |

## Editor

| Symptom | Fix |
|---|---|
| A node cannot be selected | It is locked or hidden (Scene tree toggles), or another node is above it — check the *rect select* tool or unlock it |
| Zoom looks wrong | `zoom 100%` means design pixels map 1:1; tap the percentage to reset, `0` to refit, `F` to frame the selection |
| Changes are not in the game | Press Save (Ctrl+S): Run and export read the scene from disk, and the header shows `*` while there are unsaved changes |
| The editor opened with an old copy of the scene | A crash left `.recovery/<scene>.autosave.json`; the editor offers it on start (or *Scene → Recover autosave*) |
| A tool seems stuck | `Esc` cancels the current tool/gesture; switching tools also cancels it |
| Asset edits do nothing | The resource cache keys on file timestamps; *Files → Reimport* refreshes metadata, *Debug → Reload scripts* recompiles scripts |

## Runtime

| Symptom | Fix |
|---|---|
| Nothing happens in play mode | Play mode advances physics, scripts, particles and audio; the editor's edit mode only previews animations/particles. Check the Output tab for script errors |
| Script errors | The failing instance is disabled and the error is logged with file + line; fix it and use *Debug → Reload scripts* |
| No sound | Buses could be muted (Audio panel); `AudioSource.spatial` with the listener far away will attenuate to silence |
| Performance drops | Open the Profiler tab: it reports real per-phase milliseconds, draw calls, batches, culled items, active nodes and particles. The usual culprits are huge numbers of dynamic bodies (enable sleeping) or very large tilemaps |
| Texture looks blurry | Import a higher-resolution asset or raise `Pixels / Unit`; for pixel art, enable nearest filtering and *Pixel Perfect* |

## Export

| Symptom | Fix |
|---|---|
| "Project exported" but no APK | This is expected on a stock device: there is no `aapt2`/`d8`/`apksigner`. Use a bundled player template APK, a Gradle build on a desktop, or the ZIP export |
| Gradle build not attempted | The **Export** panel's *Check toolchain* lists what was found; install a JDK + Gradle (Termux works) or build the generated project elsewhere |
| Install fails with "invalid package" | The APK is v1-signed only; enable v2 signing on a desktop build, or install with `adb install --bypass-low-target-sdk-block` |
