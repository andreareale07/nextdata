# Changelog

Formato basato su [Keep a Changelog](https://keepachangelog.com/it-IT/1.1.0/);
il progetto segue il [Semantic Versioning](https://semver.org/lang/it/).

## [Non rilasciato]

### Aggiunto
- Provider SMB (SMB 3.1.1, firmato e cifrato; modalità "Compatibile" senza cifratura, scelta per profilo), dietro la feature `smb` dell'engine: resta disattivato nelle build di rilascio finché il controllo di sicurezza (docs/smb-gate.md) non è completo.
- Interfaccia: campi condivisione e sicurezza per i profili SMB; nuova barra in basso su telefono e pagina profilo compatta su desktop.

### Corretto
- Una cartella di partenza impostata nel profilo non viene più sostituita dall'ultima cartella visitata (riportava alla radice).

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

[Non rilasciato]: https://github.com/andreareale07/nextdata/compare/v0.1.0-beta.1...HEAD
[0.1.0-beta.1]: https://github.com/andreareale07/nextdata/releases/tag/v0.1.0-beta.1
