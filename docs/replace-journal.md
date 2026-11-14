# Sostituzione remota sicura e journal (M2)

Comandi (vedi anche `docs/protocol.md`): `session.replace`, `replace.pending`, `replace.recover`, `replace.forget`.

## `session.replace`
`{session, ref, local_path, expect_version?, base_sha256?, resolution?:"normal"|"overwrite"|"copy", confirm_unverified?:bool, recovery_dir?}`
→ `{entry, version, replaced:{atomic, verified:"content"|"size", residue:[...]}, attempt, resolution}`

- `local_path` deve essere un file regolare (mai un link) e **non deve cambiare durante il salvataggio** (il chiamante usa un'istantanea). Il motore ne calcola SHA-256 e dimensione e verifica sul remoto di aver scritto esattamente quello.
- `normal` richiede `expect_version` (la versione remota su cui si è basata la modifica). Il motore rilegge la versione e risponde `CONFLICT` (`params.reason`):
  `changed` (versione diversa), `remote_missing`, `content_changed` (stesse size+mtime ma contenuto diverso, rilevato con `base_sha256`), `unverifiable` (identità non dimostrabile: provider senza ETag e nessun hash, o file > 16 MiB; si procede solo con `confirm_unverified:true`), `replace_in_progress`, `pending_recovery` (esiste un tentativo non concluso sullo stesso percorso: va riconciliato prima), `recovery_copy_too_large`.
- `overwrite`: sovrascrittura consapevole; con `recovery_dir` il remoto corrente viene prima copiato lì (≤ 256 MiB).
- `copy`: il file modificato viene creato accanto con nome libero `nome (modifica NextData).ext`; il remoto non cambia.
- Una sola sostituzione per percorso e profilo alla volta.
- `replaced.atomic` è `false` per SFTP e per WebDAV (non garantita dal protocollo), `true` per SMB (rename con sostituzione) e S3 (PUT per-oggetto).
- `verified`: `content` = riletto e confrontato (≤ 16 MiB), `size` = solo dimensione.
- Errori: `SAVE_UNCERTAIN` (esito non stabilito: nessuna pulizia, il journal resta), `params.original_preserved:true` (fallito, originale intatto).

## Sequenza SFTP / provider senza replace atomico (`replace.rs`)
1. journal `Begin`, `UploadIntent` → upload su `<path>.nd-part-<attempt>` → `Uploaded` → verifica dimensione → `TmpVerified`
2. `BackupIntent` → rename originale → `<path>.nd-bak-<attempt>` → `BackupDone`
3. `SwapIntent` → rename temporaneo → originale → `SwapDone`
4. verifica del nuovo originale (contenuto se ≤ 16 MiB) → `NewVerified`
5. `BackupDeleteIntent` → elimina il backup (solo ora) → `Completed`

**Non è atomica**: tra 2 e 3 il percorso originale non esiste; un altro client può vederlo assente. I rename dei provider non sostituiscono mai un file esistente (controllo "esiste" + rename: finestra residua minima lato server). `put(overwrite=true)` su SFTP è rifiutato (`UNSUPPORTED`): niente più "remove prima".

## Journal (`journal.rs`)
File per tentativo `<data_dir>/journal/<attempt>.ndj`, solo append, fsync dopo ogni record **prima** dell'operazione remota che annuncia (record "Intent") e dopo l'esito. Ogni record è cifrato (AEAD, chiave HKDF(PMK,"journal")) con AAD = attempt + numero di sequenza: riordino, rimozioni intermedie e spostamenti fra tentativi falliscono l'autenticazione. Lettura: si fidano solo i record validi fino al primo troncato (`truncated_*`) o corrotto (`corrupt_record`); il resto è `damage`.
Il record `Begin` contiene profilo, percorso, size, SHA-256 e versione attesa: senza di esso non si tocca nulla.

## Riconciliazione (`reconcile`, `recover`)
Decide dallo **stato remoto**, non dai codici d'errore. Si toccano solo i nomi derivati dall'attempt.

| Stato remoto trovato | Azione | Esito |
|---|---|---|
| originale = contenuto nuovo (verificato) | elimina backup e temporaneo nostri | `new_in_place` |
| originale presente, ≠ nuovo, nessun backup | elimina il solo temporaneo | `original_intact` |
| originale assente, backup presente | rinomina backup → originale (non sostituisce); poi elimina il temporaneo | `original_restored` (se il rename fallisce: `uncertain`, nulla cancellato) |
| originale assente, nessun backup, temporaneo = contenuto nuovo | rinomina temporaneo → originale | `new_in_place` |
| originale presente ≠ nuovo **e** backup presente (altro client ha scritto) | nessuna | `uncertain` (tutto conservato) |
| nulla di riconoscibile / server non raggiungibile | nessuna | `uncertain` / `unreachable` (journal resta) |
| journal vuoto o con primo record troncato | nulla era iniziato (Begin precede ogni operazione remota) | `original_intact` |
| primo record corrotto (`corrupt_record`) | nessuna (i nomi non sono affidabili) | `uncertain` |

Dopo un errore ambiguo durante `session.replace` la stessa riconciliazione gira subito (stesso codice) prima di rispondere; se il server non risponde ancora l'esito è `SAVE_UNCERTAIN` e nessun file viene toccato. `replace.recover {session}` ripete la riconciliazione per i tentativi non conclusi del profilo; `replace.pending` li elenca; `replace.forget {attempt, confirm:true}` è la decisione esplicita dell'utente per scartare un journal `uncertain` (non tocca i file remoti, che restano come prove).

## Limiti residui
- Non atomica su SFTP e su WebDAV; il backup SFTP lascia una finestra senza originale.
- `rename` provider = "esiste?" poi rename: un altro client può creare il percorso fra i due passi (S/WebDAV/SMB: rischio basso, non eliminabile lato client). Il rollback non sovrascrive mai ciò che esiste, ma in quella micro-finestra dipende dal server.
- La verifica del contenuto rilegge il file solo se ≤ 16 MiB; oltre si confronta la dimensione.
- `posix-rename@openssh.com` non è raggiungibile con l'API di `russh-sftp 3.0.1` (vedi `docs/spike-open-preview.md`).
- WebDAV/SMB/S3 usano ancora il nome temporaneo `.nd-part` fisso dentro il proprio `put`; SMB/WebDAV/S3 non usano backup (sostituzione del server).
- Test su SMB reale (OMV) e su server SFTP con interruzioni TCP reali: **NON ESEGUITO** (le interruzioni sono simulate da un provider con fault injection).
