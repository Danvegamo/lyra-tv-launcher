# Instala el APK del launcher en el Fire TV y vuelve a activar el servicio de accesibilidad.
# `adb install -r` desvincula el servicio: sin él no funcionan el botón Home ni la búsqueda guiada (Crunchyroll e IPTV).
# Uso: .\instalar.ps1 -Tele <IP-del-Fire-TV>                 (adb por red activado en Opciones de desarrollador)
#      .\instalar.ps1 -Tele <IP> -Apk ruta\al\archivo.apk   (por omisión, el APK de depuración recién compilado)
# Usage: .\instalar.ps1 -Tele <fire-tv-ip> [-Apk path] [-Forzar]
param([Parameter(Mandatory = $true)][string]$Tele, [string]$Apk = "", [switch]$Forzar)
$ErrorActionPreference = "Stop"
$tv = if ($Tele -match ":") { $Tele } else { "${Tele}:5555" }
$servicio = "co.davidvega.launcher/co.davidvega.launcher.sistema.HomeAccessibilityService"

adb connect $tv | Out-Null

# Reinstalar mata el launcher y deja ver el inicio de Amazon: si alguien está usando la tele (algo reproduce o la actividad en primer plano
# cambió en los últimos 2 min), se espera hasta 10 min. -Forzar se salta la espera.
if (-not $Forzar) {
    for ($i = 0; $i -lt 40; $i++) {
        $suena = [int](adb -s $tv shell "dumpsys media_session | grep -c 'state=3'")
        $ahora = [datetime]::ParseExact((adb -s $tv shell date "+%Y-%m-%d_%H:%M:%S").Trim(), "yyyy-MM-dd_HH:mm:ss", $null)
        $ult = adb -s $tv shell "dumpsys usagestats | grep MOVE_TO_FOREGROUND | tail -1"
        $reciente = $false
        if ($ult -match 'time="([\d-]+ [\d:]+)"') { $reciente = (($ahora - [datetime]::Parse($Matches[1])).TotalSeconds -lt 120) }
        if ($suena -eq 0 -and -not $reciente) { break }
        Write-Host "Alguien usa la tele (reproduce=$suena, cambio reciente=$reciente): espero 15 s..."
        Start-Sleep -Seconds 15
    }
}
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot "app\build\outputs\apk\debug\app-debug.apk" }
if (-not (Test-Path $Apk)) { throw "No encuentro el APK: $Apk (compila con .\gradlew.bat assembleDebug)" }
adb -s $tv install -r $Apk
adb -s $tv shell settings put secure enabled_accessibility_services $servicio
adb -s $tv shell settings put secure accessibility_enabled 1
# Autocuración: con este permiso la app reactiva sola la accesibilidad y la escucha de medios tras un reinicio.
adb -s $tv shell pm grant co.davidvega.launcher android.permission.WRITE_SECURE_SETTINGS
$activo = adb -s $tv shell settings get secure enabled_accessibility_services
if ($activo -notmatch "HomeAccessibilityService") { throw "El servicio de accesibilidad no quedó activo: $activo" }
Write-Host "Instalado y servicio de accesibilidad activo."
