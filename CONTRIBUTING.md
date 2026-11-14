# Contribuire a NextData

Grazie dell'interesse! Questo documento spiega come proporre modifiche in modo che vengano riviste rapidamente.

## Prima di iniziare

- Per bug e idee apri una [issue](https://github.com/andreareale07/nextdata/issues) usando i modelli.
- Per modifiche non banali, apri prima una issue: evita lavoro duplicato o in direzioni non condivise.
- **Vulnerabilità di sicurezza:** non usare le issue pubbliche, vedi [SECURITY.md](SECURITY.md).

## Struttura e regole di fondo

- `engine/` (Rust) fa rete, crittografia, filesystem. `app/` (Kotlin) fa solo interfaccia e stato.
- UI e motore comunicano solo con i messaggi JSON descritti in [docs/protocol.md](docs/protocol.md).
  Un test di architettura impedisce alla UI di importare codice di rete, crittografia o filesystem: non aggirarlo.
- Il motore non produce testo per l'utente: usa codici d'errore. Ogni stringa nuova va in **italiano e inglese**
  (`composeResources/values` e `values-it`); un test verifica che le due lingue siano allineate.
- Niente segreti, host reali o dati personali nel codice, nei test o nei log.

## Ambiente di sviluppo

Requisiti: Rust stabile, JDK 21, Android SDK/NDK. Comandi in [README](README.md#compilare-dai-sorgenti) e
[app/NOTES.md](app/NOTES.md).

```bash
cd engine && cargo test --lib          # unit test del motore
cd app && ./gradlew :shared:allTests   # test della UI con motore finto
```

I test di conformità e sicurezza del motore richiedono server locali reali: usa
[`infra/test-env/run-tests.ps1`](infra/test-env/run-tests.ps1).

## Pull request

1. Crea un branch dal `main`: `feature/…`, `fix/…`.
2. Mantieni la PR piccola e focalizzata; aggiungi o aggiorna i test.
3. Compila il modello di PR: cosa cambia, perché, come è stato verificato. Dichiara ciò che **non** hai potuto
   eseguire, senza arrotondare.
4. La CI deve essere verde.

### Messaggi di commit

Formato [Conventional Commits](https://www.conventionalcommits.org): `feat:`, `fix:`, `docs:`, `refactor:`,
`test:`, `build:`, `ci:`. Esempio: `fix(engine): non inoltrare Authorization nei redirect cross-origin`.

## Licenza dei contributi

Inviando una pull request accetti che il tuo contributo sia distribuito con la [licenza MIT](LICENSE) del progetto.
