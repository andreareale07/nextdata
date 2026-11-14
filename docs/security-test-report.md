# NextData — Security test report

Data: 2026-11-14. Ambito: Rust engine (`nd_engine`), bridge JNI/C ABI, app Android (release) e Windows.
Convenzione: ciò che non è stato eseguito è marcato **NON ESEGUITO (causa)**.

## Riferimenti metodologici
- OWASP MASVS / MASTG (aree STORAGE, NETWORK, PLATFORM, CODE, RESILIENCE): ordine dei controlli.
- Attacchi a client da server malevolo (traversal nei listing, redirect, XXE): casi trasformati in test automatici.
- `cargo audit` (RustSec) per le dipendenze.

## Risultati

| Area | Verifica | Esito | Dove |
|---|---|---|---|
| NETWORK | Nessuna credenziale SSH prima dell'accettazione dell'impronta host | PASS | `engine/tests/security.rs` |
| NETWORK | Server SSH con soli algoritmi deboli (SHA-1, CBC, ssh-rsa) rifiutato prima di ogni prompt | PASS | idem |
| NETWORK | Chiave host cambiata: blocco, sostituzione solo esplicita; nessuna credenziale inviata | PASS | `conformance.rs`, emulatore |
| NETWORK | TLS con pin SPKI (WebDAV) | PASS | `conformance.rs` |
| NETWORK | Redirect cross-origin: header Authorization non inoltrato | PASS | `security.rs` (evil_dav) |
| NETWORK | Credenziali in chiaro verso host pubblico negate da policy | PASS | `conformance.rs` |
| PLATFORM | WebDAV malevolo: XXE, entity bomb, risposta infinita, href con traversal/duplicati/nomi riservati | PASS | `security.rs` |
| PLATFORM | Nomi remoti ostili (`..\..\x`, `CON`, bidi) non escono dalla cartella di destinazione | PASS | `security.rs` |
| PLATFORM | Body più/meno lungo del dichiarato: nessun file risulta completato | PASS | `security.rs` |
| STORAGE | Store cifrato (ChaCha20-Poly1305): bit-flip, troncamento, chiave errata rifiutati; 20.000 nonce unici | PASS | `security.rs` |
| STORAGE | Android (release, emulatore): password, host, utente, nome profilo, impronta NON presenti in chiaro nei dati dell'app | PASS | controllo dinamico con `adb root` + grep |
| STORAGE | `pmk.bin` = 60 byte (IV+chiave+tag): PMK avvolta dal Keystore, non in chiaro | PASS | idem |
| STORAGE | Permessi dir dati `drwx------`, backup disattivato, regole di estrazione dati | PASS | `apk-audit.sh`, emulatore |
| STORAGE | Nessun segreto/host/impronta in logcat durante connessione e listing | PASS | emulatore |
| PLATFORM | `run-as` rifiutato (release non debuggable) | PASS | emulatore |
| PLATFORM | Solo `MainActivity` (launcher) esportata; `TransferService` non esportato; nessun deep link | PASS | manifest |
| PLATFORM | Screenshot/registrazione schermo consentiti | Decisione del progetto: nessun `FLAG_SECURE` (non è un rischio in sé; da rivalutare solo se emergono bug collegati) | |
| CODE | APK release con R8 + shrink risorse, firmato (v2), allineamento 16 KB | PASS | `apk-audit.sh`, `apksigner` |
| CODE | Binari: DLL con ASLR/DEP/high-entropy; `.so` con RELRO, BIND_NOW, stack non eseguibile | PASS | analisi header |
| CODE | Dipendenze: `cargo audit` | PASS con 1 eccezione motivata (RUSTSEC-2023-0071, `engine/.cargo/audit.toml`) | |
| ROBUSTEZZA | Fuzz: 3.000 comandi casuali, 80.000 input ai parser, 300 import ostili senza panic | PASS | `security.rs` |

## Difetti trovati e corretti in questo giro
1. **Timeout di connessione SFTP incluso nel tempo di decisione dell'utente** (20 s): il tempo del prompt TOFU ora non conta. Test di regressione `sftp_slow_user_prompt_does_not_time_out` (prompt da 23 s) e verifica su emulatore con conferma a 28 s. Non ho eseguito il test contro il codice precedente, quindi non ho una dimostrazione "rosso → verde" del test.
2. Release build: `isMinifyEnabled` era falso; ora R8 + regole keep per il bridge JNI (verificato: motore reale funziona in release minificata).

## NON ESEGUITO
- **Symlink in upload**: test saltato su questo host (privilegi insufficienti per creare symlink su Windows). Il codice li ignora ma non è dimostrato qui.
- **cargo-fuzz / fuzzing con copertura**: NON ESEGUITO (non installato); i fuzz eseguiti sono casuali, non guidati dalla copertura.
- **Proxy intercettante (mitmproxy/Burp) e analisi dinamica con Frida/objection**: NON ESEGUITO (non installati).
- **Dispositivi fisici**: NON ESEGUITO; tutto su emulatore x86_64 (userdebug, `adb root`).
- **Server SFTP e S3 malevoli** (equivalenti di `evil_dav` per traversal nei listing): coperti solo indirettamente dai test sui nomi ostili e dal sanitizzatore listing; non c'è un server SFTP/S3 ostile dedicato.
- **Lint Android release**: disattivato (`checkReleaseBuilds=false`) per un limite del workaround AarMetadata di Compose 1.12.
- **Keystore di firma**: chiave di test locale (git-ignored). Una firma di produzione va generata e custodita a parte.
- **Installer Windows (MSI/EXE) e firma Authenticode**: NON ESEGUITO.
- **Revisione esterna / penetration test indipendente**: non sostituito da questi test.

## Rischi residui noti
- RUSTSEC-2023-0071 (Marvin, `rsa` via `russh`): impatto locale sulla firma con chiave RSA propria; rivalutare a ogni release di `russh`.
- Una sessione SFTP morta viene riportata come `IO` con detail "session closed" e la UI la riconosce da quel testo (fragile).
