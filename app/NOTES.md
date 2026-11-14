# NextData app (Kotlin Multiplatform, Compose Multiplatform)

Modules: `shared` (KMP android+jvm: UI, state, protocol codec, `Engine`, `FakeEngine`), `engine-binding` (pure JVM: `NativeBridge`, `JniEngine`, `NativeLoader`),
`androidApp`, `desktopApp`. The UI never builds paths, does crypto or network: it uses only `ref`/`parent_ref`/`breadcrumbs` and maps `err.code` to string resources.

## Commands (Windows, Git Bash)
```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.13.9-hotspot"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:/Progetti/Nextdata/app/.tmp"   # see "Environment quirks"
./gradlew.bat :shared:allTests                 # jvm + android host unit tests (+ architecture + i18n guards, jvm only)
./gradlew.bat :desktopApp:test                 # DPAPI tests; real-engine E2E is skipped unless ND_ENGINE_E2E=1
ND_ENGINE_E2E=1 ./gradlew.bat :desktopApp:test # E2E with the real nd_engine.dll and the `fake` protocol
./gradlew.bat :desktopApp:run --args="--fake"  # or ND_FAKE_ENGINE=1; without the flag the real DLL is used
./gradlew.bat :desktopApp:createDistributable  # app-image (no WiX needed)
./gradlew.bat :androidApp:assembleDebug        # APK
```
Artifacts: `desktopApp/build/compose/binaries/main/app/NextData/NextData.exe` (app-image, 173 MB incl. JRE, bundles the engine DLL),
`androidApp/build/outputs/apk/debug/androidApp-debug.apk`. `ND_APP_HOME` overrides `%LOCALAPPDATA%\NextData` (tests). Debug fake engine on Android: `adb shell am start -n app.nextdata.android/.MainActivity --ez fake true`.

## Environment quirks
- On this machine JDK 21 cannot open a `Selector` (AF_UNIX temp-dir problem: "Unable to establish loopback connection"), which breaks the Gradle daemon. Workaround: `-Djdk.net.unixdomain.tmpdir=<short path>` through `JAVA_TOOL_OPTIONS` (folder `app/.tmp`, git-ignored). An empty `C:\tmpj` folder was created during diagnosis and can be deleted.
- Engine libraries are picked up automatically: Android via `jniLibs.srcDir(engine/dist/android)`, desktop via task `stageEngine` (DLL becomes classpath resource `native/windows-x64/nd_engine.dll`, extracted at runtime to `%LOCALAPPDATA%\NextData\native\<crc>\` and loaded with `System.load`; `ND_ENGINE_DLL` overrides).

## Decisions / deviations
- Versions as requested (Kotlin 2.4.21, CMP 1.12.2, AGP 9.5.1, ...). CMP 1.12.2 brings androidx.compose 1.12.1 whose AAR metadata demands compileSdk 37 (not installed): compileSdk=36 and the `*AarMetadata` check tasks are disabled in `androidApp` and `shared` (targetSdk 35, minSdk 26). Build and APK are fine; not run on a device.
- Compose resources cannot be given a custom environment (internal API), so the in-app language switch sets the platform default `Locale` (`applyAppLocale`) and re-creates the composition (`key(tag)`); dates/sizes use explicit tags. Desktop menus follow the same tag.
- Protocol assumptions (protocol.md is silent): extra setting key `collision`; `lang` is `it`/`en` (absent = system); S3 profiles are saved with `auth:"none"` and `secret_key` in `secrets`; `settings.set` is sent with the full settings object (unknown keys preserved); `profiles.save` without `secrets` is assumed to keep the stored secret; `profiles.export` exports all profiles; `EventListener` is `app.nextdata.bridge.EventListener` (fun interface). Reason code `SMB_GATE_NOT_PASSED` (real engine) has its own string; other unknown reason codes show a generic text. `session.delete` of a folder returned `deleted:2` (counts children), so the UI does not assume 1 per ref.
- Android uploads copy picked documents/folders into the private cache under their real names (the engine names remote files from the local name, `/proc/self/fd/N` would lose it) and wipe them afterwards; downloads stage in the private cache then copy to the chosen tree via ContentResolver. No foreground service (optional, not done).
- Desktop DPAPI via JNA `Crypt32Util` (no PowerShell, no secret on a command line); an unreadable key file is never overwritten.
- Hidden files: filtered in the UI by dot-prefix according to `show_hidden` (display only).
- Transfers: queue of 2 parallel jobs; each job is one `transfer.*` command; cancel -> coroutine cancel -> `cancel(op)`; failed/cancelled jobs keep staged resources for retry and release them on removal.

## Verified (real output)
- `:shared:allTests`: jvm 76 tests (codec 14, transport/correlation/cancel 8, view models/controller/transfers/prompts 32, mapping 11, architecture 3, i18n 8) all pass; Android host tests 65 (common tests) pass.
- i18n guard: all 23 protocol error codes + every string exist in IT and EN with identical placeholders; no hardcoded literals in `ui/`.
- Architecture guard: `shared` and `engine-binding` sources reference none of `java.net`, `javax.crypto`, `java.security`, `java.nio.file`.
- Real engine E2E (`ND_ENGINE_E2E=1`, engine 0.1.0): init, profile save/list, connect (`fake`), list (demo: Backup, Documenti, Foto, Vuota, LEGGIMI.txt), mkdir, rename, upload, download (progress events received, content verified), recursive delete, close, profile delete - passes.
- Desktop `--fake` run: window opens, IT menu bar, profiles panel and browser listing (screenshot `screenshot-desktop-fake.png`). Packaged app-image launched with the real engine: DPAPI key file created, DLL extracted, engine data dir created.
- `assembleDebug` produces the APK (41 MB); `zipalign -P 16` passes; it contains `libnd_engine.so` for arm64-v8a, armeabi-v7a, x86_64.

## UX round (see UX_GUIDELINES.md, UX_TEST_REPORT.md, docs-screens/)
- New: token system, density, list/grid, three-zone desktop and two-pane tablet layouts, details pane, quick filter, shortcuts, context menu, resizable columns, onboarding, profile guide with live validation and "Test connection", redesigned trust dialogs, security chip, transfers with speed/ETA/pause queue, favorites + recents + last folder (stored in engine settings keys `density`, `view_mode`, `theme`, `onboarded`, `ask_download_location`, `nav_width_dp`, `details_open`, `last_refs`, `recents`; the real engine preserves them), window geometry in `%LOCALAPPDATA%/NextData/window.properties`.
- Android: persisted SAF permission + reuse of last folder, specific storage errors, foreground service `TransferService`, probe + automatic reconnect on resume (the OS drops sockets of backgrounded apps after ~15-20 s), predictive back opt-in.
- Verified on the emulator and on desktop with the real engine: see UX_TEST_REPORT.md. Tests: `:shared:allTests`, `:desktopApp:test` (E2E fake + `ND_ENGINE_E2E_SFTP="host|port|user|pass"` for a real SFTP server).
- Open engine issue: the 20 s connect timeout includes the time spent on the trust prompt.

## NOT verified
- TalkBack/Narrator, RTL, reduce-motion on a device, predictive-back animation, MSI/EXE installers, release/minified builds, Android language switch and profile export/import re-run in the last round.
