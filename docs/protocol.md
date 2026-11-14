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
SERVER_ANOMALY, LIMIT_EXCEEDED, IMPORT_INVALID, PASSPHRASE_INVALID, NOT_INITIALIZED, SESSION_CLOSED, PROTOCOL_DISABLED.
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
| `session.mkdir` | `{session, parent_ref, name}` | `{entry:Entry}` |
| `session.rename` | `{session, ref, new_name}` | `{entry:Entry}` |
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
          s3?:{bucket, region, access_key, path_style:bool}, has_secret:bool, color?:string }
Entry   { name, ref, kind:"file"|"dir"|"symlink"|"other", size:int|null, mtime_ms:int|null, safe_local_name }
```
`ref` è opaco: Kotlin non costruisce né analizza percorsi (usa `ref`, `parent_ref`, `breadcrumbs`).
Nuovi nomi (mkdir/rename) passano come testo utente e il motore li valida (`INVALID_ARGUMENT`).
