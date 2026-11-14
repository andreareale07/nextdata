# Security test suite: hostile servers + hostile input + fuzz loops. Only 127.0.0.1, synthetic data.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$data = Join-Path $here 'data'
New-Item -ItemType Directory -Force $data | Out-Null
$py = Join-Path $here '.venv\Scripts\python.exe'
$log = Join-Path $data 'evil.log'
$procs = @()
try {
  $p = Start-Process -FilePath $py -ArgumentList @("`"$here\evil_dav.py`"", '22250', '22251') -PassThru -WindowStyle Hidden -RedirectStandardOutput "$data\evil.out" -RedirectStandardError "$data\evil.err"
  $procs += $p
  for ($i=0; $i -lt 60; $i++) { if ((Test-Path "$data\evil.out") -and (Select-String -Path "$data\evil.out" -Pattern 'ready' -Quiet)) { break }; Start-Sleep -Milliseconds 250 }
  $env:ND_TEST_EVIL = "22250|22251|$here\evil_dav.py.22251.log"
  $env:ND_TEST_PY = $py
  $mg = (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Directory -Filter "BrechtSanders*" | ForEach-Object { Join-Path $_.FullName "mingw64\bin" } | Select-Object -First 1)
  $env:PATH = "$env:USERPROFILE\.cargo\bin;$mg;" + $env:PATH
  Push-Location (Join-Path $here '..\..\engine')
  cargo test --test security --release -- --nocapture --test-threads=1
  $rc = $LASTEXITCODE
  Pop-Location
  exit $rc
} finally {
  foreach ($p in $procs) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
}
