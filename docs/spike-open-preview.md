# Spike M0 — apertura/modifica file remoti (esiti)

Branch `feature/open-preview-edit`. Stato al 2026-11-14. Piano: vedi piano approvato (v2). Qui solo ciò che è stato **verificato**; tutto il resto è `NON ESEGUITO (causa)`.

| Spike | Esito | Evidenza / causa |
|---|---|---|
| P1 `posix-rename@openssh.com` in SFTP | **Verificato staticamente: NON raggiungibile con l'API pubblica di `russh-sftp 3.0.1`.** `SftpSession.session` è privato e `features` (hardlink, fsync, statvfs, expand_path, limits) non include posix-rename; il server non espone la lista estensioni. `RawSftpSession::extended(request, data)` esiste ma richiede di usare `RawSftpSession` direttamente o di vendorizzare/patchare il crate (come già fatto per `smb`). Payload = due stringhe SFTP (come `hardlink`). Il comportamento reale sui server resta NON ESEGUITO (nessun test contro OpenSSH ancora scritto) | `rawsession.rs:713`, `session.rs:31,57-63,230` |
| P9 `FileId`/`ChangeTime` nel crate `smb` | **Disponibili nel crate**: `FileBasicInformation.change_time`, `FileIdInformation`, `FileInternalInformation`, `FileAllInformation` (`smb-fscc-0.12.1`). Il `FileId` di `resource.rs` è l'handle di sessione, **non** l'indice NTFS: serve `FileIdInformation`. Disponibilità reale su Samba/OMV: NON ESEGUITO (serve la query contro OMV) | `query_file_info.rs:199,211` |
| P2 WebDAV `If-Match`/`If:` su MOVE/PUT | NON ESEGUITO (nessun server di prova scelto; serve test su Apache/Nextcloud/`dav_server.js`) | — |
| P3 S3 `If-Match` su PUT | NON ESEGUITO (moto/MinIO non interrogati) | — |
| P4 `ACTION_EDIT` su app Android reali (HyperOS) | NON ESEGUITO (serve il telefono e app candidate) | — |
| P5 pattern di salvataggio editor Windows | NON ESEGUITO (serve prova interattiva) | — |
| P6 throughput base64 su JNI | NON ESEGUITO (serve prototipo e misura) | — |
| P7 ExoPlayer con MP4 `moov` in coda | NON ESEGUITO (serve Media3 e file di prova) | — |
| P8 formati immagine Skia desktop | NON ESEGUITO (serve prova di decodifica) | — |

## Conseguenza sul piano (da decidere)
SFTP: per sostituire in modo atomico serve (a) patch/vendor di `russh-sftp` per esporre la sessione grezza e le estensioni annunciate, oppure (b) riscrivere il provider su `RawSftpSession`, oppure (c) accettare il fallback con rollback (`orig → orig.nd-bak-<id>`, `part → orig`, rimozione bak), dichiarato "non atomico". Raccomandazione: iniziare con (c) in M2 e valutare (a) dopo la prova contro OpenSSH.
