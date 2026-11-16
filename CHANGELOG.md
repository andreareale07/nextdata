# Changelog

Formato basato su [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/);
il progetto segue il [Semantic Versioning](https://semver.org/lang/it/).

## [Non rilasciato]

### Aggiunto
- **Trascinamento su desktop** degli elementi selezionati: rilasciati su una cartella dell'elenco (o su una cartella del percorso in alto) vengono **spostati**; rilasciati in Esplora file vengono **copiati sul PC**. Nuovo comando del motore `session.move` (non sostituisce mai nulla). Il trascinamento fuori dall'app è limitato a 512 MiB; oltre si usa «Scarica».

## [0.1.0-beta.4]

Novità rispetto a 0.1.0-beta.3: una sola voce «Carica», avanzamento dei trasferimenti sempre visibile e trasferimenti che non si interrompono tornando ai profili.

### Modificato
- Una sola voce «Carica» al posto di tre: su desktop un unico dialogo per file e cartelle insieme; su telefono un riquadro guidato per aggiungere file e cartelle in più passaggi e caricare tutto in un unico trasferimento.
- Barra di avanzamento unica per tutti i trasferimenti (elementi, percentuale, velocità), visibile nel browser, nella schermata dei profili e nelle impostazioni; si tocca per aprire i trasferimenti.

### Corretto
- Tornare alla schermata dei profili non interrompe più i trasferimenti in corso: la connessione resta aperta finché non sono terminati.

## [0.1.0-beta.3]

Novità rispetto a 0.1.0-beta.2: SMB è attivo.

### Aggiunto
- **SMB attivo** nelle build di rilascio (Windows e Android): SMB 3.1.1 firmato e cifrato, con modalità «Compatibile» (firmata, senza cifratura) scelta per profilo. Verificato contro Samba (11 casi: firma, cifratura, NTLMv2, server SMB 2.1 rifiutato, nomi pericolosi) e su emulatore Android; esiti in docs/smb-gate.md.

### Noto
- SMB: non eseguiti i controlli sui reparse point di un server Windows, sul telefono arm64 fisico e sulla modalità «Compatibile» con un server reale. Un server Samba configurato per seguire i link simbolici fuori dalla share li mostra come file normali: nessun client può riconoscerli.

## [0.1.0-beta.2]

Novità rispetto a 0.1.0-beta.1: aprire e modificare i file remoti senza scaricarli, caricamento di file e cartelle insieme, meno domande quando si guarda un file con un'altra app.

### Aggiunto
- **Aprire senza scaricare**: un tocco su un file lo apre direttamente. Viewer interni per immagini (con scorrimento tra le immagini della cartella), testo (con modifica e salvataggio), PDF (Android) e audio/video letti a blocchi dal server (Android). Gli altri tipi si aprono con un'altra app; "Scarica" resta nel menu ⋮.
- **Modificare con altre app e salvare sul server**: le modifiche fatte in un'altra app vengono rilevate dal contenuto e caricate con verifica; nessun file viene sovrascritto in silenzio (conflitti, copia di recupero, giornale cifrato per i salvataggi interrotti). Schermata "Modifiche da salvare" nelle Impostazioni e opzione "Carica le modifiche fatte in altre app".
- **Caricamento di file e cartelle insieme** (menu "+" e menu di caricamento): si aggiungono file e cartelle in più passaggi e si carica tutto in un unico trasferimento. Utile su telefono, dove il selettore di sistema sceglie o file o una cartella.
- Provider SMB (SMB 3.1.1, firmato e cifrato; modalità "Compatibile" senza cifratura, scelta per profilo), dietro la feature `smb` del motore: **resta disattivato nelle build di rilascio** finché il controllo di sicurezza (docs/smb-gate.md) non è completo.
- Interfaccia: campi condivisione e sicurezza per i profili SMB; nuova barra in basso su telefono e pagina profilo compatta su desktop.

### Modificato
- Aprire un file con un'altra app per guardarlo non genera più, a ogni ritorno nell'app, la domanda "nessuna modifica rilevata, vuoi importare una versione?". La domanda compare solo se scegli esplicitamente di modificare il file dal viewer, e una volta sola per apertura. Le modifiche vere vengono comunque rilevate e salvate.
- Il trascinamento (drag & drop) su desktop non blocca più la finestra con cartelle grandi e non carica due volte un file già contenuto in una cartella trascinata.
- Aggiornate le dipendenze e le azioni della pipeline di rilascio.

### Corretto
- Una cartella di partenza impostata nel profilo non viene più sostituita dall'ultima cartella visitata (riportava alla radice).

### Noto
- Non ancora collaudati su dispositivi reali: video Android, app esterne reali, SMB su server reale (disattivato nelle build), interruzioni di rete.
- Gli installer Windows non sono ancora firmati (Authenticode).

## [0.1.0-beta.1]

Prima versione beta pubblica.

### Aggiunto
- Client SFTP (password e chiave privata), WebDAV e S3/compatibili.
- App Windows (EXE, MSI, ZIP portabile) e Android (APK, AAB).
- Navigazione, creazione cartelle, rinomina, eliminazione ricorsiva, upload/download di file e cartelle.
- Coda di trasferimenti con velocità, tempo stimato, pausa, annulla e riprova.
- Preferiti, recenti, ripristino dell'ultima cartella, importazione/esportazione cifrata dei profili.
- Interfaccia in italiano e inglese, tema chiaro/scuro, layout adattivi telefono/tablet/desktop.

### Sicurezza
- Verifica dell'impronta host/certificato prima di qualsiasi credenziale; blocco in caso di cambio impronta.
- Store dei profili cifrato (ChaCha20-Poly1305) con chiave protetta da Android Keystore / DPAPI.
- Difese contro server malevoli (path traversal, XXE, redirect cross-origin) coperte da test automatici.

### Noto
- SMB disabilitato in attesa del relativo controllo di sicurezza.
- Gli installer Windows non sono ancora firmati.

[Non rilasciato]: https://github.com/andreareale07/nextdata/compare/v0.1.0-beta.4...HEAD
[0.1.0-beta.4]: https://github.com/andreareale07/nextdata/compare/v0.1.0-beta.3...v0.1.0-beta.4
[0.1.0-beta.3]: https://github.com/andreareale07/nextdata/compare/v0.1.0-beta.2...v0.1.0-beta.3
[0.1.0-beta.2]: https://github.com/andreareale07/nextdata/compare/v0.1.0-beta.1...v0.1.0-beta.2
[0.1.0-beta.1]: https://github.com/andreareale07/nextdata/releases/tag/v0.1.0-beta.1
