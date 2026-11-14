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
