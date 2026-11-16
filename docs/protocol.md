# NextData — Protocollo del bridge (B4, "narrow waist")

Il motore Rust (`engine/`, libreria `nd_engine`) e la UI Kotlin comunicano SOLO tramite messaggi JSON UTF-8.
Il motore non emette mai testo per l'utente: solo `code` + `params`. Kotlin mappa i codici su risorse i18n.

## Funzioni native (JNI, classe Kotlin `app.nextdata.bridge.NativeBridge`, `object` con `@JvmStatic external`)
```
fun submit(commandJson: String): Long      // ritorna op id (== campo "op" del comando)
fun setListener(listener: EventListener?)  // EventListener { fun onEvent(json: String) } ; chiamato su thread del motore
fun cancel(op: Long)
fun version(): String
```
Libreria: Windows `nd_engine.dll`, Android `libnd_engine.so` (`System.loadLibrary("nd_engine")`).
Nota: i callback arrivano su thread del motore: Kotlin cambia subito dispatcher e non blocca mai.

## Comando (Kotlin → motore)
`{"op": <u64 scelto da Kotlin, >0, univoco>, "cmd": "<nome>", "args": { ... }}`

## Eventi (motore → Kotlin), una riga JSON per evento
- `{"op":N,"ev":"ok","data":{...}}`            terminale
- `{"op":N,"ev":"err","err":{"code":"...","params":{...},"retryable":false}}`   terminale
- `{"op":N,"ev":"cancelled"}`                  terminale
- `{"op":N,"ev":"progress","data":{"bytes":123,"total":456|null,"item":"nome file","items_done":1,"items_total":3}}`
- `{"op":N,"ev":"prompt","data":{"prompt_id":"p1","kind":"trust_host"|"trust_cert"|"password"|"passphrase", ...}}`
  - trust_host: `{host,port,algo,fingerprint,prior:null|"SHA256:..."}` ("prior" non nullo = chiave CAMBIATA: la UI mostra un allarme; il motore comunque blocca se l'utente non conferma esplicitamente la sostituzione: `replace:true`)
  - trust_cert: `{host,port,spki_sha256,subject,self_signed:bool,reason}`
  - password/passphrase: `{profile_name}`  (chiede il segreto se non memorizzato)
  Risposta di Kotlin: comando `prompt.reply` `{"prompt_id":"p1","accept":true|false,"value":"..."?,"replace":false?}`.

## Codici di errore (`err.code`)
INTERNAL, INVALID_ARGUMENT, NOT_FOUND, ALREADY_EXISTS, PERMISSION_DENIED, AUTH_FAILED, HOST_KEY_REJECTED,
HOST_KEY_CHANGED, TLS_UNTRUSTED, TLS_ERROR, NETWORK, TIMEOUT, UNSUPPORTED, POLICY_DENIED, IO, DISK_FULL,
SERVER_ANOMALY, LIMIT_EXCEEDED, IMPORT_INVALID, PASSPHRASE_INVALID, NOT_INITIALIZED, SESSION_CLOSED, PROTOCOL_DISABLED, VERSION_CHANGED, RANGE_UNSUPPORTED, CONFLICT (`params.reason`), SAVE_UNCERTAIN.
`params` tipici: `{"name":..., "path":..., "detail":"stringa tecnica non localizzata, solo diagnostica"}`.

## Comandi
| cmd | args | ok.data |
|---|---|---|
| `init` | `{data_dir, pmk_b64 (32 byte, dal keystore OS)}` | `{api_version:1, version, protocols:[{id,enabled,reason_code?}]}` (id: sftp, webdav, s3, smb, fake) |
| `profiles.list` | `{}` | `{profiles:[Profile]}` |
| `profiles.save` | `{profile:Profile, secrets?:{password?,private_key?,passphrase?,secret_key?}}` | `{profile:Profile}` (id generato se vuoto) |
| `profiles.delete` | `{id}` | `{}` (crypto-erase dei segreti e pin) |
| `profiles.export` | `{passphrase, options:{favorites:bool,settings:bool,pins:bool}}` | `{data_b64}` file `.ndprofile` cifrato (mai credenziali) |
| `profiles.import` | `{data_b64, passphrase, apply:bool}` | `{profiles:[Profile], conflicts:[{id,name}] , applied:bool}` (apply=false → anteprima) |
| `session.connect` | `{profile_id}` | `{session:"s1", home_ref}` (può emettere `prompt`) |
| `session.list` | `{session, ref, page_token?, limit? (default 200, max 500), sort?:"name"\|"size"\|"mtime", desc?:bool}` | `{ref, parent_ref:null\|string, breadcrumbs:[{name,ref}], entries:[Entry], next_token:null\|string, total_hint?:int}` |
| `session.stat` | `{session, ref}` | `{entry:Entry, version:RemoteVersion or null}` (`version` solo per file) |
| `read.open` | `{session, ref}` | `{handle, size, version}` (fissa la versione; max 4 handle per sessione, 8 globali, TTL 60 s di inattività; chiusi con `session.close`; `LIMIT_EXCEEDED` oltre) |
| `read.range` | `{handle, offset, length (1..262144)}` | `{data_b64, eof}`; `offset` oltre la fine → vuoto con `eof:true`; timeout 15 s (`TIMEOUT`); cancellabile; `VERSION_CHANGED` se l'oggetto è cambiato (S3/WebDAV con ETag), `RANGE_UNSUPPORTED` se il server ignora `Range` |
| `read.close` | `{handle}` | `{}` |
| `session.replace` | `{session, ref, local_path, expect_version?, base_sha256?, resolution?, confirm_unverified?, recovery_dir?}` | `{entry, version, replaced:{atomic,verified,residue}, attempt, resolution}` — vedi `docs/replace-journal.md` |
| `replace.pending` / `replace.recover` / `replace.forget` | `{profile_id?}` / `{session}` / `{attempt, confirm:true}` | `{pending:[...]}` / `{recovered:[{attempt,path,outcome,reason}]}` / `{}` |
| `open.*`, `cache.*` | vedi `docs/open-edit.md` | workspace locale dei file aperti (prepare/check/save/import/recover/list/export/discard) |
| `session.mkdir` | `{session, parent_ref, name}` | `{entry:Entry}` |
| `session.rename` | `{session, ref, new_name}` | `{entry:Entry}` |
| `session.move` | `{session, refs[], dest_ref}` | `{moved:int}` — sposta in un'altra cartella della stessa sessione; non sostituisce mai (`ALREADY_EXISTS`), rifiuta la radice (`POLICY_DENIED`), una cartella dentro se stessa o un bersaglio che non è una cartella (`INVALID_ARGUMENT`); se un elemento fallisce, quelli precedenti sono già spostati (`params.moved`) |
| `session.delete` | `{session, refs:[string], recursive:bool}` | `{deleted:int}` (progress per item) |
| `session.close` | `{session}` | `{}` |
| `transfer.download` | `{session, refs:[string], dest_dir, collision:"rename"\|"skip"\|"fail"}` | `{local_paths:[string]}` (cartelle ricorsive; `.part` + rename no-replace; progress) |
| `transfer.upload` | `{session, local_paths:[string], parent_ref, collision:"rename"\|"skip"\|"fail"}` | `{entries:[Entry]}` (cartelle ricorsive; `local_paths` può essere `/proc/self/fd/N` su Android) |
| `prompt.reply` | vedi sopra | `{}` |
| `settings.get` / `settings.set` | `{}` / `{settings:{...}}` | `{settings:{ "lang"?:..., "show_hidden":bool, ...}}` |
| `favorites.list/add/remove` | `{profile_id}` / `{profile_id,ref,name}` / `{profile_id,ref}` | `{favorites:[{ref,name}]}` |

## Tipi
```
Profile { id, name, protocol:"sftp"|"webdav"|"s3"|"smb"|"fake", host, port:int|0(=default), username,
          auth:"password"|"key"|"none", base_path:"", tls:bool(webdav/s3: https),
          s3?:{bucket, region, access_key, path_style:bool}, share:"" (smb: nome della condivisione),
          smb_mode:""|"strict"|"relaxed" (smb), has_secret:bool, color?:string }
Entry   { name, ref, kind:"file"|"dir"|"symlink"|"other", size:int|null, mtime_ms:int|null, safe_local_name }
```
`ref` è opaco: Kotlin non costruisce né analizza percorsi (usa `ref`, `parent_ref`, `breadcrumbs`).
Nuovi nomi (mkdir/rename) passano come testo utente e il motore li valida (`INVALID_ARGUMENT`).

RemoteVersion { size, mtime_ms, mtime_resolution_ms (SFTP/WebDAV/S3: 1000), etag?, version_id?, strength:"strong"|"weak"|"none" }
`strength` strong = identificatore del server (ETag S3, `getetag` WebDAV, `x-amz-version-id`); weak = solo size+mtime (SFTP, SMB, WebDAV senza ETag): non è prova di identità.
Nota: `read.range` usa base64 in JSON (≤ 256 KiB per chiamata); il throughput è da misurare (spike P6). Per SFTP/SMB `read_at` oggi riapre il file a ogni chiamata (corretto ma non ottimizzato).

## SMB (feature `smb` dell'engine; vedi docs/smb-gate.md e docs/adr/0001)
- `Profile.share` obbligatorio (senza barre); `Profile.username` accetta `DOMINIO\utente`; `Profile.port` 0 = 445.
- `smb_mode`: `strict` (predefinito: SMB 3.1.1, firmato e cifrato) o `relaxed` (3.1.1 firmato, senza cifratura; scelta esplicita dell'utente per profilo).
- Policy: solo indirizzi privati, loopback e link-local, verificati sull'indirizzo risolto. `POLICY_DENIED` con `params.rule`: `smb_public_host`, `smb_dialect` (server senza 3.1.1), `smb_negotiation`, `smb_name` (nome non ammesso su una condivisione Windows).
- `PERMISSION_DENIED` con `params.hint = "server_requires_encryption"` (solo profili `relaxed`): accesso negato, spesso perché il server impone la cifratura; la UI suggerisce di controllare la sicurezza.
- Senza la feature `smb` l'engine riporta `protocols[smb].enabled=false` con `reason_code: SMB_GATE_NOT_PASSED` e risponde `PROTOCOL_DISABLED`.
