# Verifica LATO SERVER che le sessioni SMB dell'engine siano davvero 3.1.1, firmate e (in strict) cifrate.
# Per ogni caso apre una sessione reale (test smb_hold_session_for_inspection) e legge `smbstatus` nel container.
# Nota: Samba ignora `server signing = disabled` per SMB3 (la firma resta attiva), quindi la modalita' 'nosign'
# non puo' provare che il client rifiuti server non firmanti: lo garantisce il codice del client
# (session/channel.rs: messaggio ne' firmato ne' cifrato => errore). Questo script mostra cio' che viene negoziato.
# Uso: .\smb-inspect.ps1   (richiede Docker; PASS/FAIL per caso, exit 1 se qualcosa non va)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$env:PATH = "$env:USERPROFILE\.cargo\bin;" + $env:PATH
$engine = (Resolve-Path (Join-Path $here '..\..\engine')).Path
$failed = @()
function Inspect-Case($mode, $smbMode, $wantEncrypted) {
  & "$here\smb-server.ps1" start -Mode $mode | Out-Null
  $env:ND_TEST_SMB = '127.0.0.1|22230|ndshare|nd|ndpass'
  $env:ND_TEST_SMB_MODE = $smbMode
  $env:ND_TEST_SMB_HOLD = '40'
  $p = Start-Process cargo -ArgumentList 'test', '--features', 'smb', '--test', 'conformance', 'smb_hold_session', '--', '--nocapture' -WorkingDirectory $engine -PassThru -WindowStyle Hidden
  $line = $null
  $ErrorActionPreference = 'Continue'
  for ($i = 0; $i -lt 45 -and -not $line; $i++) {
    Start-Sleep -Seconds 2
    $out = (docker exec nd-samba smbstatus -p 2>&1 | Out-String)
    $line = ($out -split "`n" | Where-Object { $_ -match 'SMB\d' } | Select-Object -First 1)
  }
  $ErrorActionPreference = 'Stop'
  Write-Host ("[{0}/{1}] {2}" -f $mode, $smbMode, ($line -replace '\s+', ' '))
  $ok = [bool]$line -and ($line -match 'SMB3_11') -and ($line -match 'AES-128-(GMAC|CMAC)|AES-128-CCM|HMAC')
  $enc = [bool]$line -and ($line -match 'AES-(128|256)-(GCM|CCM)')
  if ($wantEncrypted) { $ok = $ok -and $enc } else { $ok = $ok -and -not $enc }
  Write-Host ("  => " + $(if ($ok) { 'PASS' } else { 'FAIL' }))
  if (-not $ok) { $script:failed += "$mode/$smbMode" }
  $p | Wait-Process -Timeout 90 -ErrorAction SilentlyContinue
  Remove-Item Env:\ND_TEST_SMB_HOLD
}
try {
  Inspect-Case 'default' 'strict'  $true
  Inspect-Case 'default' 'relaxed' $false
  Inspect-Case 'nosign'  'relaxed' $false
} finally {
  & "$here\smb-server.ps1" start -Mode default | Out-Null
}
if ($failed.Count) { Write-Host "FALLITI: $($failed -join ', ')"; exit 1 } else { Write-Host 'ispezione OK'; exit 0 }
