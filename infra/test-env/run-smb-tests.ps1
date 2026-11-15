# Prova SMB dell'engine contro il Samba di prova (Docker) in piu' modalita'. Richiede Docker.
# Uso: .\run-smb-tests.ps1   (usa smb-server.ps1; dati sintetici su 127.0.0.1)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:PATH = "$env:USERPROFILE\.cargo\bin;" + $env:PATH
$failed = @()
function Run-Case($mode, $smbMode, $expect) {
  & "$here\smb-server.ps1" start -Mode $mode | Out-Null
  $env:ND_TEST_SMB = '127.0.0.1|22230|ndshare|nd|ndpass'
  $env:ND_TEST_SMB_MODE = $smbMode
  if ($expect) { $env:ND_TEST_SMB_EXPECT = $expect } else { Remove-Item Env:\ND_TEST_SMB_EXPECT -ErrorAction SilentlyContinue }
  Push-Location (Join-Path $here '..\..\engine')
  $ErrorActionPreference = 'Continue'
  cargo test --features smb --test conformance smb_ -- --nocapture --test-threads=1 2>&1 | Select-Object -Last 6
  $rc = $LASTEXITCODE
  $ErrorActionPreference = 'Stop'
  Pop-Location
  if ($rc -ne 0) { $script:failed += "$mode/$smbMode/$expect" }
  Write-Host ("== modo={0} smb_mode={1} atteso={2} => {3}" -f $mode, $smbMode, $expect, $(if ($rc -eq 0) { 'OK' } else { 'FALLITO' }))
}
function Run-Links {
  & "$here\smb-server.ps1" start -Mode default | Out-Null
  docker exec nd-samba sh -c 'mkdir -p /outside/dir && echo nd-outside-secret > /outside/secret.txt && echo inner > /outside/dir/inner.txt && cd /shares/ndshare && rm -f lnk_out lnk_dir && ln -s /outside/secret.txt lnk_out && ln -s /outside/dir lnk_dir' | Out-Null
  $env:ND_TEST_SMB = '127.0.0.1|22230|ndshare|nd|ndpass'
  $env:ND_TEST_SMB_LINKS = '1'
  Push-Location (Join-Path $here '..\..\engine')
  $ErrorActionPreference = 'Continue'
  cargo test --features smb --test conformance smb_links_on_the_server -- --nocapture --test-threads=1 2>&1 | Select-Object -Last 6
  $rc = $LASTEXITCODE
  $ErrorActionPreference = 'Stop'
  Pop-Location
  Remove-Item Env:\ND_TEST_SMB_LINKS
  # il bersaglio dei link non deve essere stato toccato (cancellare il link non cancella cio' che indica)
  $left = (docker exec nd-samba sh -c 'cat /outside/secret.txt; ls /outside/dir' 2>&1 | Out-String)
  $intact = ($left -match 'nd-outside-secret') -and ($left -match 'inner.txt')
  docker exec nd-samba sh -c 'rm -rf /outside; rm -f /shares/ndshare/lnk_out /shares/ndshare/lnk_dir' | Out-Null
  if ($rc -ne 0 -or -not $intact) { $script:failed += 'links' }
  Write-Host ("== modo=links => {0} (bersagli intatti: {1})" -f $(if ($rc -eq 0) { 'OK' } else { 'FALLITO' }), $intact)
}
try {
  Run-Links
  Run-Case 'default'  'strict'  $null
  Run-Case 'signing'  'strict'  $null
  Run-Case 'encrypt'  'strict'  $null
  Run-Case 'encrypt'  'relaxed' $null
  Run-Case 'nosign'   'strict'  $null   # Samba ignora la disattivazione della firma in SMB3: la sessione resta firmata (vedi smb-inspect.ps1)
  Run-Case 'nosign'   'relaxed' $null
  Run-Case 'ntlmv2'   'strict'  $null
  Run-Case 'ntlmv2'   'relaxed' $null
  Run-Case 'nontlm'   'strict'  'NETWORK'   # server senza NTLM: sessione rifiutata (0xc0000418), nessun ripiego; il codice e' generico, non AUTH_FAILED
  Run-Case 'old'      'strict'  'POLICY_DENIED'
  Run-Case 'old'      'relaxed' 'POLICY_DENIED'
} finally {
  & "$here\smb-server.ps1" start -Mode default | Out-Null
}
if ($failed.Count) { Write-Host "FALLITI: $($failed -join ', ')"; exit 1 } else { Write-Host 'tutti i casi SMB OK'; exit 0 }
