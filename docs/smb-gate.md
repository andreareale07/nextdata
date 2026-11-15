# SMB — criteri di abilitazione (gate)

> Stato: **bozza ricostruita**. Il testo originale del gate SMB della specifica v2.2.1 non è disponibile nel repository; questo documento fissa i criteri sulla base dei requisiti SMB della v2.2 e delle decisioni successive. Da confrontare con la specifica originale se ritrovata.

## Principio
SMB è disponibile **solo sulle piattaforme** che superano **integralmente** la suite di conformità e i test di sicurezza qui sotto, eseguiti su ambiente rappresentativo. Un test non eseguito non conta come superato (si riporta "NON ESEGUITO" con la causa).

## Requisiti del client (modalità rigida, predefinita)
- Dialetto minimo **SMB 3.1.1**; nessun SMB1; nessun downgrade accettato.
- **Firma obbligatoria** su ogni sessione autenticata; sessioni guest/anonime rifiutate.
- **Cifratura obbligatoria**.
- Autenticazione **NTLMv2** (utente, password, dominio opzionale). Kerberos e certificati: fuori ambito.
- Host ammessi: solo indirizzi privati, loopback e link-local, verificati sull'indirizzo effettivo dopo la risoluzione DNS. Host pubblici rifiutati.
- Percorsi e nomi: stessi controlli degli altri provider, più nomi riservati Windows; link simbolici e reparse point non seguiti.

## Modalità attenuata (deroga esplicita, per profilo)
Scelta dall'utente profilo per profilo, mai predefinita, con avviso in interfaccia: SMB 3.x **senza cifratura**. Restano obbligatori: firma, dialetto 3.1.1, NTLMv2, restrizione sugli host. Vedi ADR-0001.

## Suite da superare (per piattaforma)
Ambiente: server Samba di prova (`infra/test-env/smb-server.ps1`) nelle modalità `default`, `signing`, `encrypt`, `nosign`, `old`.

| Controllo | Esito atteso |
|---|---|
| Connessione 3.1.1 + cifratura, lettura/scrittura/elenco/rinomina/elimina | riuscita |
| Server con massimo SMB 2.1 | rifiutato |
| Server che impone cifratura, client senza | rifiutato |
| Firma disattivata/non verificabile | rifiutato |
| Credenziali errate | `AUTH_FAILED` |
| Nomi con `..`, separatori, nomi riservati Windows nei listing | non producono percorsi fuori dalla share |
| Link simbolici / reparse point | non seguiti |
| Host pubblico | `POLICY_DENIED` |
| Rinomina su destinazione esistente | `ALREADY_EXISTS`, nessuna sovrascrittura |
| Annullamento di trasferimenti grandi | rilascio risorse, nessun file parziale visibile |
| `cargo audit` sull'engine | nessuna vulnerabilità non motivata |

Piattaforme di partenza: Windows e Android. iOS: da verificare su hardware Apple, **NON ESEGUITO**.

## Condizioni di arresto
Qualunque controllo fallito su una piattaforma lascia SMB disabilitato su quella piattaforma (`SMB_GATE_NOT_PASSED`) finché non è corretto e rieseguito.

## Esiti dell'ultima esecuzione (2026-11-15)
Ambiente: Samba 4.23 in Docker (`infra/test-env/smb-server.ps1`), engine compilato con `--features smb`. Script: `run-smb-tests.ps1` (conformità, 11 casi + link), `smb-inspect.ps1` (ispezione lato server con `smbstatus`).

| Controllo | Esito | Come |
|---|---|---|
| Connessione 3.1.1 + cifratura, lettura/scrittura/elenco/rinomina/elimina, collisioni, annullo di trasferimenti grandi | **superato** (Windows, e Android x86_64 su emulatore, strict e relaxed) | suite di conformità; Android: binario di test cross-compilato eseguito via adb contro Samba su 10.0.2.2 |
| Server con massimo SMB 2.1 | **superato** (`POLICY_DENIED`, strict e relaxed) | modo `old` |
| Server che impone la cifratura | **superato** (la sessione si cifra; strict e relaxed) | modo `encrypt`. Il caso "client senza cifratura" non esiste: nessun profilo disattiva la cifratura (strict = richiesta, relaxed = consentita) |
| Firma | **superato, con avvertenza** | `smbstatus` conferma SMB3_11 + AES-128-GMAC in strict e in relaxed anche con `server signing = disabled`. Samba IGNORA quella opzione in SMB3, quindi non si può costruire un server che non firma: il rifiuto di messaggi non firmati e non cifrati è garantito dal codice del client (`vendor/smb/src/session/channel.rs`, `_verify_incoming`) e non da un test end-to-end |
| NTLMv2 | **superato** | modo `ntlmv2` (`ntlm auth = ntlmv2-only`) riesce; modo `nontlm` (`ntlm auth = disabled`) è rifiutato (codice `NETWORK`, 0xc0000418: nessun ripiego su altri meccanismi) |
| Credenziali errate, share inesistente | **superato** (`AUTH_FAILED`, `NOT_FOUND`) | conformità |
| Nomi `..`, separatori, nomi riservati Windows, `:`, `?`, `*` | **superato** (`POLICY_DENIED`) | conformità + test unitari `to_smb_path` |
| Host pubblico, share vuota/con `\`, `smb_mode` non valido | **superato** | `smb_policy_refusals` |
| Rinomina su destinazione esistente | **superato** (`ALREADY_EXISTS`) | conformità |
| `cargo audit` | **superato con eccezione motivata** | nessuna vulnerabilità; `RUSTSEC-2023-0071` (`rsa`) ignorata e motivata in `engine/.cargo/audit.toml` e nell'ADR-0001; 1 avviso "non mantenuto" (`pin-utils`, non una vulnerabilità) |
| Link simbolici su Samba | **LIMITE NOTO** | questo Samba segue i link anche fuori dalla share e li presenta come file/cartelle normali: per qualunque client SMB sono indistinguibili. Il client non va in errore e il bersaglio non è stato toccato (era di proprietà di root, quindi non modificabile dall'utente SMB). Un `elimina ricorsivo` su un link a cartella che l'utente può modificare attraversa il link: dipende dalla configurazione del server (`wide links`), non rilevabile dal client |
| Reparse point su server Windows | **NON ESEGUITO** (serve un server Windows; creare share/account locali è una modifica di sistema non fatta) | il codice li riporta come link e non li segue (`reparse_point()`) |
| Android arm64 su telefono fisico | **superato** (2026-11-15, strict e relaxed: conformità + rifiuti di policy, 4 test) | binario di test arm64 eseguito via adb sul telefono contro il Samba del PC tramite `adb reverse` (127.0.0.1:22230); build di test, non l'APK dell'app |
| Modalità "Compatibile" su server reale | **NON ESEGUITO** (provata solo su Samba di prova e dall'utente solo in "Sicuro") | |
| iOS | **NON ESEGUITO** (serve un Mac) | |
