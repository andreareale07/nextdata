# ADR-0001 — SMB: modalità attenuata per profilo e rischio residuo `rsa`

Stato: proposta, da approvare. Data: 2026-11-14.

## Contesto
La specifica v2.2 richiede per SMB: dialetto 3.1.1, firma e cifratura obbligatorie, rifiuto del downgrade. La v2.2.1 chiede di mantenerli invariati. L'esigenza concreta è collegarsi a un NAS OpenMediaVault via Samba; non tutti i NAS espongono la cifratura SMB.

## Decisione 1 — Modalità attenuata
Il profilo SMB ha due modalità: `strict` (predefinita: 3.1.1, firma e cifratura obbligatorie) e `relaxed` (scelta esplicita dell'utente per quel profilo: 3.1.1 e firma obbligatorie, cifratura non imposta). La modalità attenuata mostra un avviso in interfaccia e non è mai attivata automaticamente.

Questa è una **deroga** al requisito "invariato" della v2.2.1: richiede approvazione esplicita.

## Decisione 2 — Libreria
Crate Rust `smb` 0.12.1 (MIT), versione fissata, senza compressione, Kerberos e NetBIOS. Verificato: compila per Windows e Android; contro Samba 4.23 negozia 3.1.1 con cifratura; rifiuta server con massimo SMB 2.1; scarta messaggi non firmati né cifrati per sessioni autenticate.

## Copia locale di `smb` (una sola modifica)
`smb` 0.12.1 richiede esattamente `sspi` 0.21.3, che richiede esattamente `curve25519-dalek` 5.0.0-rc.1; `russh` (SFTP) richiede la 5.0.0 finale: i due non possono convivere nello stesso programma. `sspi` 0.23.1 non ha il conflitto. Il crate `smb` è quindi incluso in `engine/vendor/smb` con **un'unica modifica**: in `Cargo.toml`, `sspi = "=0.23.1"` invece di `=0.21.3`. Il codice sorgente è invariato. Verificato: compila con `russh` e supera la suite SMB (conformità e sicurezza) contro Samba.
Impegni: riesame a ogni nuova versione di `smb`; quando una versione ufficiale usa `sspi` ≥ 0.23, tornare alla dipendenza da crates.io ed eliminare la copia.

## Rischio residuo — `rsa` 0.10.0-rc.18 (RUSTSEC-2023-0071)
`rsa` 0.10.0-rc.18 è già presente nel motore tramite `russh` (SFTP), con l'avviso accettato in `engine/.cargo/audit.toml`. `smb`/`sspi` non aggiungono un rischio nuovo: nel codice di `sspi` `rsa` non compare nel modulo NTLM, quindi con la sola autenticazione NTLM non dovrebbe essere eseguito. Il codice resta compilato.
Mitigazioni: Kerberos e certificati fuori ambito; `cargo audit` a ogni build; riesame a ogni nuova versione di `smb`/`russh`. Da rivalutare prima di introdurre Kerberos o autenticazione con certificato.

## Conseguenze
- Più superficie di dipendenze (circa 290 crate, parte in versione pre-release).
- La modalità attenuata riduce la riservatezza (non l'integrità) del traffico sulla rete.
