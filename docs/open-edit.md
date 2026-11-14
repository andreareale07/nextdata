# Aprire e modificare file remoti (workspace del motore)

Il motore tiene una cartella per documento: `<data_dir>/workspace/<doc_id>/` con `current/<nome>` (il file che vede l'utente o l'app esterna), `base` (copia della versione scaricata/salvata, solo ≤ 64 MiB), `snap-<n>` (istantanee immutabili da caricare), `doc.nd` (record cifrato: profilo, riferimento remoto, stato, versione e SHA-256 della base, istantanee). La verità sulle modifiche è l'**hash del contenuto**, non mtime/size.

## Comandi
| cmd | args | risultato |
|---|---|---|
| `open.prepare` | `{session, ref, cache_limit?}` | `{doc, from_cache, remote_changed, over_quota?, version}`: scarica o riusa dalla cache (rilegge la versione remota; un documento non pulito non viene mai sovrascritto) |
| `open.check` | `{doc_id, stable_ms?=1000, accept_empty?}` | `{doc, changed, copies:[...], unstable?/locked?/missing?/suspicious?}`: istantanea per copia + hash; file a 0 byte con base non vuota = `suspicious` (non diventa un salvataggio) |
| `open.save` | `{session, doc_id, resolution?:normal\|overwrite\|copy, confirm_unverified?, recovery_dir?}` | `{doc, saved}`; usa `session.replace` (conflitti, journal, verifica) sull'ultima istantanea. `copy`: il documento segue la copia creata. Errori → stato `conflict`/`uncertain`/`save_failed` (mai perdita locale) |
| `open.import` | `{doc_id, local_path}` | istantanea scelta dall'utente (`imported`); non carica |
| `open.recover` | `{session}` | riconcilia i journal del profilo e risolve i documenti `uncertain`: remoto = istantanea → `clean`; altro → `save_failed`; assente → `conflict`; irraggiungibile → resta `uncertain` |
| `open.list` | `{profile_id?}` | `{docs:[...needs_attention], unreadable:[ids]}` |
| `open.mark_open` / `open.release` | `{doc_id}` | segna/rilascia "app esterna può usarlo" |
| `open.export` | `{doc_id, dest_dir}` | copia file corrente e istantanee in una cartella scelta |
| `open.discard` | `{doc_id, confirm:true}` | elimina (solo decisione esplicita) |
| `cache.stats` / `cache.purge` | `{}` / `{max_idle_secs?, open_max_idle_secs?}` | usati / eliminabili / protetti; pulizia solo eliminabili |

## Stati e cosa è permesso
| Stato | Significato | Pulizia automatica | Azioni |
|---|---|---|---|
| `downloading` | scarico in corso | interrotto all'avvio ⇒ scartato (nessun dato utente) | — |
| `clean` | = base verificata | **solo se** non aperto da app esterna e inattivo (24 h; 7 giorni se aperto) | prepare, check |
| `modified` | differisce dalla base | **mai** | check, save, import, export |
| `imported` | versione dell'utente in attesa | **mai** | save, export |
| `saving` | salvataggio in corso | **mai**; dopo crash ⇒ `uncertain` | — |
| `save_failed` | upload fallito, originale preservato | **mai** | save (riprova), export |
| `uncertain` | esito ignoto | **mai** | `open.recover` (nessun retry cieco: `open.save` risponde `CONFLICT pending_recovery`) |
| `conflict` | remoto cambiato / identità non dimostrabile | **mai** | save con `copy` o `overwrite` (con copia di recupero), export |
| `orphaned` | profilo cancellato con lavoro pendente | **mai** | export, discard (con conferma) |

Cancellazione profilo: i documenti `clean` non aperti sono eliminati; gli altri passano a `orphaned`. Quota (500 MiB predefinita): prima di un nuovo download si liberano solo voci eliminabili; se non basta `over_quota:true` (avviso), mai cancellando lavoro protetto.

## Limiti residui
- Spazio libero su disco non controllato dal motore (solo la quota); `DISK_FULL` arriva dal sistema.
- La base non viene copiata oltre 64 MiB (resta lo SHA-256).
- Un'app che tiene un blocco esclusivo sul file rende `open.check` → `locked` (da riprovare).
- Rilevamento di modifiche dal vivo (watcher), apertura con app esterne e importazione guidata sono lato app (M5).
