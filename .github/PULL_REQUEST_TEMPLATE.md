## Cosa cambia

<!-- Descrizione breve e il perché. Collega la issue: Closes #123 -->

## Tipo

- [ ] Correzione di bug
- [ ] Nuova funzionalità
- [ ] Refactoring / manutenzione
- [ ] Documentazione / CI

## Come è stato verificato

<!-- Comandi eseguiti ed esito reale. Ciò che non hai potuto eseguire scrivilo come "NON ESEGUITO (causa)". -->

- [ ] `cargo test --lib` (engine)
- [ ] `./gradlew :shared:allTests` (app)
- [ ] Provato su Windows / Android

## Checklist

- [ ] Nessun segreto, host reale o dato personale nel codice, nei test o nei log
- [ ] Nuove stringhe presenti in italiano **e** inglese
- [ ] Il confine UI ↔ motore resta solo `docs/protocol.md` (aggiornato se cambia)
- [ ] `CHANGELOG.md` aggiornato se la modifica è visibile all'utente
