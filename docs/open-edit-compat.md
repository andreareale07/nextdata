# Aprire e modificare file remoti — stato, matrici, rischi, verifiche

Stato al 2026-11-14, branch `feature/open-preview-edit` (nessun commit). Progetto e motivazioni: `docs/open-edit.md` (workspace), `docs/replace-journal.md` (sostituzione e journal), `docs/spike-open-preview.md` (spike).

## Cosa fa l'app adesso
- **Tocco su un file (telefono) / doppio clic o Invio (desktop) = apre, non scarica.** Il menu ⋮ resta per Scarica e il resto.
- Visualizzatori interni: **immagini** (zoom, scorrimento fra le immagini della cartella), **testo/codice/CSV/JSON/Markdown/log** (editor con Salva, bozza locale ogni 3 s, UTF-8/BOM/UTF-16, mai salvataggi su codifiche non riconosciute), **PDF** (Android: `PdfRenderer`; Windows: app di sistema), **audio/video** (Android: `MediaPlayer` alimentato dal server a intervalli, senza copia; Windows: copia nello spazio temporaneo e player di sistema).
- Altri formati e "Apri con altra app": copia nel workspace, app esterna, **rilevamento delle modifiche per contenuto (hash)** e salvataggio automatico verificato (o "chiedi prima", impostazione). Copie create dall'app mai caricate da sole; "Importa versione modificata" quando l'app salva altrove.
- Conflitti: Sovrascrivi (doppia conferma) / Salva come copia / Annulla; nessuna sovrascrittura silenziosa.
- Modifiche non salvate: elenco in Impostazioni → "Modifiche da salvare" (salva, copia in una cartella, elimina con conferma), avviso all'avvio, riconciliazione dei salvataggi interrotti a ogni connessione.
- File > 256 MiB da copiare in locale: chiede prima. Cache: quota 500 MiB, mai cancellato lavoro non salvato.

## Matrice per piattaforma
| Funzionalità | Windows | macOS | Android | iOS |
|---|---|---|---|---|
| Visualizzazione interna | Supportato (immagini via Skia, testo; PDF e video via app di sistema) | Non supportato (nessun target) | Supportato (immagini, testo, PDF, audio, video*) | Non supportato (nessun target) |
| Apertura esterna | Supportato (esclusi eseguibili/script) | Non supportato | Supportato (dipende dalle app installate) | Non supportato |
| Modifica esterna | Dipendente dall'app esterna | Non supportato | Dipendente dall'app (ACTION_EDIT poco onorato; fallback VIEW) | Non supportato |
| Rilevamento modifiche | Supportato (polling cartella + hash; copie, temp+rename, 0 byte) | Non supportato | Supportato con limitazioni (polling in primo piano + al ritorno) | Non supportato |
| Salvataggio automatico | Supportato con limitazioni | Non supportato | Non garantito (solo app che scrivono sul file) | Non supportato |
| Importazione modifiche | Supportato (selettore file, copie rilevate) | Non supportato | Supportato (selettore SAF) | Non supportato |
| Recovery | Supportato | Non supportato | Supportato | Non supportato |
| Streaming video/audio | Non supportato (copia + player di sistema) | Non supportato | Supportato con limitazioni (MediaPlayer di sistema: formati dipendono dal dispositivo) | Non supportato |

\* video in-app non collaudato (vedi sotto). macOS/iOS: nessun target nel repo, progetti separati.

## Matrice per protocollo
| | SFTP | SMB | WebDAV | S3 |
|---|---|---|---|---|
| Lettura a intervalli | Sì (riapre il file a ogni chiamata) | Sì (idem) | Sì con `Range` limitato + `If-Match` | Sì con `Range` limitato + `If-Match` |
| Identità remota | size+mtime (1 s): *weak* | size+mtime (100 ns): *weak* (ChangeTime/FileId disponibili nel crate, non ancora usati) | ETag se il server lo dà, altrimenti *weak* | ETag/version id: *strong* |
| Sostituzione | temp + backup + rename (non atomica, con journal e rollback) | upload temp + rename con sostituzione | PUT temp + MOVE Overwrite (atomicità non garantita) | PUT diretto (atomico per oggetto) |
| Verifica dopo il salvataggio | contenuto ≤ 16 MiB, altrimenti dimensione | idem | idem | idem |
| Recupero dopo interruzione | riconciliazione dallo stato remoto | idem | idem | idem |

## Registro dei rischi (aggiornato)
| Rischio | Stato / mitigazione realizzata | Residuo |
|---|---|---|
| Perdita file remoto in SFTP | Mai `remove` per primo; backup/rollback; journal; sweep di crash su ogni evento e modalità (test) | Finestra senza originale su SFTP; `posix-rename` non raggiungibile con `russh-sftp 3.0.1` |
| Conflitto non visto | `expect_version` obbligatoria; hash del contenuto per i weak ≤ 16 MiB; oltre: serve conferma | Provider weak e file > 16 MiB: finestra non eliminabile senza ETag |
| Cancellazione di modifiche dalla pulizia | Solo `clean` non aperti sono eliminabili (test) | — |
| Crash durante salvataggio | Journal write-ahead cifrato; `open.recover` (test) | — |
| App esterna non restituisce la modifica | Messaggio esplicito + "Importa versione modificata"; mai "salvato" senza verifica | Dipende dall'app |
| File 0 byte / troncato | Mai un salvataggio (test su file reale) | — |
| Autosalvataggi in raffica | Pausa di 10 s fra upload dello stesso documento, modalità "chiedi" | — |
| Cache in chiaro su disco | Cartella privata dell'app; record cifrati | Contenuti non cifrati |
| Download enormi involontari | Conferma sopra 256 MiB; quota cache | — |
| Throughput bridge base64 | Misurato: 38,9 MiB/s (JNI desktop, fake) | Android/rete reali non misurati |

## Verifiche eseguite (2026-11-14)
- Motore Rust: `cargo test --lib` 21, `--test security` 12, `--test replace_crash` 8 (sweep di crash su ogni evento × 3 modalità + link loss transitorio + altro client + journal danneggiato), `--test workspace` 10, `--test conformance` 13 contro fake/SFTP/WebDAV/S3 locali (`infra/test-env/run-tests.ps1`): range, stat, sostituzione, conflitti.
- App condivisa: `:shared:jvmTest` (OpenManagerTest 14, OpenWatchTest 7, TextCodecTest 5, I18n/Architecture ecc.).
- Desktop con DLL reale: `EngineE2ETest` 4 (apri/modifica/salva, throughput) e `ExternalEditE2ETest` 5 (scrittura diretta, temp+rename, copia, troncamento, ritorno senza modifiche) con `ND_ENGINE_E2E=1`.
- Emulatore Android (build release firmata con la chiave di test, motore reale, profilo demo in memoria): testo aperto/modificato/salvato ("Salvato e verificato sul server"), PNG mostrato, immagine danneggiata → messaggio, PDF mostrato, WAV riprodotto da lettura a intervalli (stato player a fine flusso, nessun errore), "Apri con altra app" con FileProvider (chooser con app).

## NON ESEGUITO (causa)
- **SMB su OpenMediaVault reale** — credenziali/host dell'utente, non nel repo (`ND_TEST_SMB`).
- **SFTP/WebDAV/S3 con interruzioni di rete reali** (non simulate dal provider di prova) — nessun server ostile disponibile.
- **Video in-app su Android** — nessun file video di prova sul server demo; solo audio WAV verificato.
- **App esterne reali su Windows** (Word, Notepad++, LibreOffice…) e **su telefono fisico HyperOS** — richiedono prova interattiva; verificati i pattern di salvataggio con operazioni sul file system.
- **Spike P2, P3, P4, P5, P7, P8** (`docs/spike-open-preview.md`) — richiedono server/app/dispositivi non disponibili qui.
- **Throughput con rete e Android reali** — solo il costo del bridge è stato misurato.
- **Accessibilità (TalkBack) e tastiera desktop** nei nuovi visualizzatori — non collaudate.
- **Immagini HEIC/AVIF/WebP e GIF animate**, **EXIF su desktop** — non provate.

## Limiti noti
- Editor di testo ≤ 5 MiB; oltre si passa all'app esterna (la lettura a pagine di grandi log non è implementata).
- Lettura a intervalli su SFTP/SMB riapre il file a ogni richiesta (corretto, non ottimizzato).
- PDF su Windows e video su Windows usano l'app di sistema (nessuna nuova dipendenza introdotta: niente PDFBox/Media3).
- Eliminazione di un profilo con lavoro pendente: i documenti passano a "orfani" ed è possibile copiarli in una cartella; manca un avviso dedicato nel dialog di cancellazione del profilo.

## Decisioni prese in autonomia (da riconfermare)
Nessuna nuova dipendenza (Android: `MediaPlayer`/`PdfRenderer` del sistema; Windows: app di sistema per PDF/video). Soglie: verifica contenuto ≤ 16 MiB, copia di recupero ≤ 256 MiB, conferma download > 256 MiB, quota 500 MiB, pausa di 10 s fra upload automatici, modalità predefinita "salva automaticamente" (disattivabile). macOS/iOS fuori ambito.
