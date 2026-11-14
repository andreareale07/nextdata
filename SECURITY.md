# Politica di sicurezza

NextData gestisce credenziali e accesso a dati remoti: le segnalazioni di sicurezza sono prese sul serio.

## Versioni supportate

| Versione | Supporto |
|---|---|
| Ultima release (inclusa l'ultima beta) | ✅ correzioni di sicurezza |
| Versioni precedenti | ❌ aggiornare all'ultima release |

## Come segnalare una vulnerabilità

**Non aprire una issue pubblica.** Usa la segnalazione privata di GitHub:

1. Vai nella scheda [**Security**](https://github.com/andreareale07/nextdata/security) del repository.
2. Scegli **Report a vulnerability**.
3. Descrivi il problema: versione, piattaforma, passi per riprodurlo, impatto atteso. Una prova di concetto aiuta.

Non includere credenziali o dati reali nella segnalazione.

## Cosa aspettarsi

- Conferma di ricezione entro **5 giorni lavorativi**.
- Una prima valutazione (accettata / non riproducibile / fuori ambito) entro **14 giorni**.
- Correzione e rilascio coordinati; il tuo contributo viene riconosciuto nelle note di rilascio, se lo desideri.
- Ti chiedo di non divulgare il problema prima che sia disponibile una correzione (divulgazione coordinata).

## Ambito

In ambito: motore (`engine/`), bridge JNI, app Android e Windows, pipeline di release.
Fuori ambito: vulnerabilità dei server a cui ci si connette, attacchi che richiedono un dispositivo già compromesso
(root, malware con privilegi equivalenti), ingegneria sociale.

## Quadro attuale

Le verifiche già eseguite e le eccezioni note (con motivazione) sono in
[docs/security-test-report.md](docs/security-test-report.md). Le dipendenze Rust sono controllate con `cargo audit`;
l'unica eccezione, motivata, è in [engine/.cargo/audit.toml](engine/.cargo/audit.toml).
