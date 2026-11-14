#!/bin/bash
# Static audit of the Android APK (MASVS-STORAGE / PLATFORM / NETWORK / CODE basics). usage: apk-audit.sh <apk>
APK="${1:-/c/Progetti/Nextdata/app/androidApp/build/outputs/apk/debug/androidApp-debug.apk}"
BT=/c/Android/sdk/build-tools/35.0.0
export MSYS_NO_PATHCONV=1
[ -f "$APK" ] || { echo "FAIL  apk not found: $APK"; exit 2; }
WAPK=$(cygpath -w "$APK")
fail=0
chk(){ if [ "$2" = "ok" ]; then echo "PASS  $1"; else echo "FAIL  $1  -> $3"; fail=1; fi; }
M=$($BT/aapt2.exe dump xmltree --file AndroidManifest.xml "$WAPK" 2>/dev/null)
[ -n "$M" ] || { echo "FAIL  cannot read manifest"; exit 2; }
echo "$M" | grep -q 'debuggable.*true' && chk "release not debuggable" bad "android:debuggable=true (debug build)" || chk "not debuggable" ok
echo "$M" | grep -q 'allowBackup.*false' && chk "allowBackup=false" ok || chk "allowBackup=false" bad "backup allowed"
echo "$M" | grep -q 'dataExtractionRules' && chk "dataExtractionRules present" ok || chk "dataExtractionRules" bad missing
echo "$M" | grep -q 'usesCleartextTraffic.*true' && chk "cleartext traffic disabled" bad "usesCleartextTraffic=true" || chk "cleartext traffic not enabled globally" ok
echo "--- permissions:"; echo "$M" | grep -E 'uses-permission' -A1 | grep -E 'name' | sed 's/.*="//;s/".*//' | sort -u
echo "--- exported components:"; echo "$M" | grep -B6 -E 'exported.*true' | grep -E 'E: (activity|service|receiver|provider)|name' | sed 's/^ *//' | head -20
mkdir -p /tmp/apkx && rm -rf /tmp/apkx/* && cd /tmp/apkx && unzip -q -o "$APK" 2>/dev/null
echo "--- secrets-like strings in dex/resources:"
n=$(strings -n 8 classes*.dex 2>/dev/null | grep -Eic 'BEGIN (RSA|OPENSSH|EC) PRIVATE|AKIA[0-9A-Z]{16}|password *= *"[^"]+"|ndpass|ndsecretkey') ; [ "$n" = "0" ] && chk "no hardcoded keys/test creds in dex" ok || chk "hardcoded secret-like strings" bad "$n matches"
echo "--- native libs:"; ls lib/*/ 2>/dev/null | tr '\n' ' '; echo
$BT/zipalign.exe -c -P 16 4 "$WAPK" >/dev/null 2>&1 && chk "16 KB zip/ELF alignment" ok || chk "16 KB alignment" bad "zipalign -P 16 failed"
exit $fail
