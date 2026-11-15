# Come compilare NextData

Il progetto ha due parti: il motore Rust (`engine/`, libreria `nd_engine`) e l'app Kotlin (`app/`, Compose Multiplatform per desktop e Android). L'app incorpora la libreria del motore, quindi **si compila prima il motore, poi l'app**.

## Requisiti
| Strumento | Versione | Serve per |
|---|---|---|
| Rust (`rustup`) | stabile recente, edizione 2024 | motore |
| JDK | 21 | app (Gradle) |
| Android SDK | piattaforma 36, build-tools 36.0.0, NDK | solo Android |
| `cargo-ndk` + target Android | `cargo install cargo-ndk --locked`; `rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android` | solo Android |
| WiX Toolset 3 | 3.x | solo installer MSI/EXE su Windows |

Imposta `JAVA_HOME` (JDK 21) e `ANDROID_HOME` (SDK). Su alcune macchine Windows Gradle non si collega al suo daemon: in quel caso imposta anche `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<cartella-scrivibile>`.

## 1. Motore (Rust)
Tutti i comandi da `engine/`.

```powershell
cargo test --lib --locked           # test unitari
cargo build --release --locked      # libreria desktop: target/release/nd_engine.dll
```
Copia la DLL dove la cerca l'app:
```powershell
Copy-Item target\release\nd_engine.dll dist\windows-x64\nd_engine.dll
```
Libreria Android (tre ABI), da `engine/`:
```powershell
$env:RUSTFLAGS = "-C link-arg=-Wl,-z,max-page-size=16384"   # Android 15+: pagine da 16 KB
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o dist/android build --release --locked
```
Le librerie finiscono in `engine/dist/android/<abi>/libnd_engine.so` (cartella ignorata da git).

**SMB.** Le build di rilascio (pipeline) sono compilate con `--features smb`. In locale, per averlo, aggiungi `--features smb` a `cargo build` / `cargo ndk … build`; senza, SMB risulta non disponibile (`PROTOCOL_DISABLED`).

## 2. App desktop (Windows)
Da `app/`:
```powershell
.\gradlew.bat :desktopApp:run                                        # avvia l'app senza installarla
.\gradlew.bat :desktopApp:packageExe :desktopApp:packageMsi :desktopApp:createDistributable
```
I pacchetti sono in `app/desktopApp/build/compose/binaries/main/` (EXE, MSI e cartella portabile `app/NextData`). Richiedono WiX 3. Per fissare la versione: `-PappVersion=1.2.3`.

## 3. App Android
Da `app/`, con le librerie del motore già in `engine/dist/android`:
```powershell
.\gradlew.bat :androidApp:assembleRelease :androidApp:bundleRelease -PappVersion=1.2.3 -PappVersionCode=10203
```
Produce APK e AAB sotto `app/androidApp/build/outputs/`. Senza firma l'APK resta non firmato.

**Firma.** Crea il file `app/.tmp/signing.properties` (ignorato da git, non va mai committato) con:
```
store=.tmp/release.jks
storePassword=...
alias=...
keyPassword=...
```
e metti il keystore in `app/.tmp/release.jks`. Il file e il keystore non devono uscire dalla tua macchina; la pipeline di rilascio li ricrea dai segreti del repository.

Installazione di prova su un telefono collegato (debug USB attivo): `adb install -r <apk>`.

## 4. Test
```powershell
# app (da app/)
.\gradlew.bat :shared:allTests :desktopApp:test --no-daemon
# motore con server di prova (da infra/test-env/)
.\run-tests.ps1          # conformità SFTP/WebDAV/S3
.\run-security.ps1       # sicurezza
.\run-smb-tests.ps1      # SMB contro Samba in Docker (richiede Docker)
```
Gradle riusa i risultati precedenti: per rieseguire davvero un test usa `cleanJvmTest` (o `cleanTest`) e `--no-build-cache`.

## 5. Rilasci
Un tag `vX.Y.Z[-beta.N]` avvia `.github/workflows/release.yml`, che compila motore, installer Windows e APK/AAB e li pubblica con i checksum. Prima del tag aggiorna `CHANGELOG.md`.

## Problemi comuni
- **"Could not connect to the Gradle daemon"**: imposta `JAVA_TOOL_OPTIONS` come sopra.
- **L'app parte ma senza SMB**: la DLL in `engine/dist/windows-x64` è stata compilata senza `--features smb`.
- **Porta occupata nei test**: i server di prova usano le porte 22222–22225 e 22230; chiudi quelli avviati a mano prima di `run-tests.ps1`.
