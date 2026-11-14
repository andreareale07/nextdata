# Starts the local test servers (SFTP/WebDAV/S3), runs the engine conformance suite against them, stops everything.
# Servers listen on 127.0.0.1 only and use synthetic data under infra/test-env/data.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$data = Join-Path $here 'data'
Remove-Item -Recurse -Force $data -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $data | Out-Null
$py = Join-Path $here '.venv\Scripts\python.exe'
$procs = @()
function Start-Srv($exe, $argv, $log) {
  $p = Start-Process -FilePath $exe -ArgumentList $argv -PassThru -WindowStyle Hidden -RedirectStandardOutput "$data\$log.out" -RedirectStandardError "$data\$log.err"
  return $p
}
function Wait-Ready($log) { for ($i=0; $i -lt 60; $i++) { if ((Test-Path "$data\$log.out") -and (Select-String -Path "$data\$log.out" -Pattern 'ready' -Quiet)) { return }; Start-Sleep -Milliseconds 250 }; throw "server $log not ready: $(Get-Content "$data\$log.err" -Raw)" }
try {
  $key = "$data\hostkey"
  $procs += Start-Srv $py @("`"$here\sftp_server.py`"", '22222', "`"$data\sftp-root`"", "`"$key`"") 'sftp'
  $procs += Start-Srv 'node' @("`"$here\dav_server.js`"", '22223', "`"$data\dav-root`"") 'dav'
  $procs += Start-Srv $py @("`"$here\s3_server.py`"", '22224') 's3'
  Wait-Ready 'sftp'; Wait-Ready 'dav'; Wait-Ready 's3'
  $env:ND_TEST_SFTP = '127.0.0.1|22222|nd|ndpass'
  $env:ND_TEST_WEBDAV = 'http|127.0.0.1|22223|/dav|nd|ndpass'
  $env:ND_TEST_PY = $py
  $env:ND_TEST_NODE = (Get-Command node).Source
  $env:ND_TEST_S3 = 'http|127.0.0.1|22224|ndbucket|ndaccess|ndsecretkey'
  $env:PATH = "$env:USERPROFILE\.cargo\bin;" + $env:PATH
  Push-Location (Join-Path $here '..\..\engine')
  cargo test --test conformance -- --nocapture --test-threads=1
  $rc = $LASTEXITCODE
  Pop-Location
  exit $rc
} finally {
  foreach ($p in $procs) { if ($p -and -not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } }
}
