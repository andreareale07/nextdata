# Server SMB di prova (Samba in Docker). Solo dati sintetici, ascolta su 127.0.0.1.
# Uso: .\smb-server.ps1 start [-Mode default|signing|encrypt|nosign|old|ntlmv2|nontlm|links] [-Port 22230]
#      .\smb-server.ps1 stop | status
# Utente nd / password ndpass, share "ndshare" (dati nel volume Docker nd-smb-data).
#  default : SMB2.1+, firma e cifratura non imposte dal server
#  signing : firma obbligatoria        encrypt : cifratura obbligatoria (SMB3)
#  nosign  : firma disattivata (il client deve rifiutare)
#  ntlmv2  : `ntlm auth = ntlmv2-only` (NTLMv1/LM rifiutati dal server)
#  nontlm  : `ntlm auth = disabled` (nessun NTLM: il client non deve ripiegare su altro, sessione rifiutata (NETWORK))
#  links   : server che SEGUE i link simbolici anche fuori dalla share (wide links), il caso peggiore per il client
#  old     : massimo SMB 2.1 (il client con minimo 3.1.1 deve rifiutare)
param(
  [Parameter(Position = 0)][ValidateSet('start', 'stop', 'status')][string]$Action = 'status',
  [ValidateSet('default', 'signing', 'encrypt', 'nosign', 'old', 'ntlmv2', 'nontlm', 'links')][string]$Mode = 'default',
  [int]$Port = 22230
)
$ErrorActionPreference = 'Stop'
$name = 'nd-samba'
$image = 'servercontainers/samba:latest'
$volume = 'nd-smb-data'  # volume Docker (non una cartella Windows: Samba non crea correttamente le directory su un bind mount NTFS)

switch ($Action) {
  'stop' { $ErrorActionPreference = 'Continue'; docker rm -f $name 2>&1 | Out-Null; Write-Host 'fermato'; break }
  'status' { docker ps --filter "name=$name" --format '{{.Names}} {{.Status}} {{.Ports}}'; break }
  'start' {
    $ErrorActionPreference = 'Continue'; docker rm -f $name 2>&1 | Out-Null; $ErrorActionPreference = 'Stop'  # se non esiste, stderr non e' un errore
    docker volume create $volume | Out-Null
    # l'immagine legge SAMBA_GLOBAL_CONFIG_<chiave con _SPACE_ al posto degli spazi>=<valore>
    $conf = [ordered]@{ 'server_SPACE_min_SPACE_protocol' = 'SMB2_10' }
    switch ($Mode) {
      'signing' { $conf['server_SPACE_signing'] = 'mandatory' }
      'encrypt' { $conf['server_SPACE_min_SPACE_protocol'] = 'SMB3'; $conf['server_SPACE_smb_SPACE_encrypt'] = 'required' }
      'nosign'  { $conf['server_SPACE_signing'] = 'disabled' }
      'ntlmv2'  { $conf['ntlm_SPACE_auth'] = 'ntlmv2-only' }
      'nontlm'  { $conf['ntlm_SPACE_auth'] = 'disabled' }
      'links'   { $conf['follow_SPACE_symlinks'] = 'yes'; $conf['wide_SPACE_links'] = 'yes'; $conf['allow_SPACE_insecure_SPACE_wide_SPACE_links'] = 'yes'; $conf['unix_SPACE_extensions'] = 'no' }
      'old'     { $conf['server_SPACE_max_SPACE_protocol'] = 'SMB2_10' }  # server vecchio: il client con minimo 3.1.1 deve rifiutare
    }
    $confArgs = @(); foreach ($k in $conf.Keys) { $confArgs += '-e'; $confArgs += "SAMBA_GLOBAL_CONFIG_$k=$($conf[$k])" }
    $env:MSYS_NO_PATHCONV = '1'
    docker run -d --name $name -p "127.0.0.1:${Port}:445" `
      -e ACCOUNT_nd=ndpass -e UID_nd=1000 `
      -e 'SAMBA_VOLUME_CONFIG_ndshare=[ndshare]; path=/shares/ndshare; valid users = nd; guest ok = no; read only = no; force user = nd' `
      @confArgs `
      -v "${volume}:/shares/ndshare" $image | Out-Null
    for ($i = 0; $i -lt 40; $i++) {
      $ErrorActionPreference = 'Continue'  # i log di docker vanno su stderr
      $log = (docker logs $name 2>&1 | Out-String)
      $ErrorActionPreference = 'Stop'
      if ($log -match 'smbd version .* started') {
        docker exec $name sh -c 'test -f /shares/ndshare/hello.txt || echo "ciao smb" > /shares/ndshare/hello.txt; chown 1000 /shares/ndshare /shares/ndshare/hello.txt' | Out-Null
        Write-Host "ready ($Mode) su 127.0.0.1:$Port"; exit 0 }
      Start-Sleep -Milliseconds 500
    }
    throw "samba non pronto"
  }
}
