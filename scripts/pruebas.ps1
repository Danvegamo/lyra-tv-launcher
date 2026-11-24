# CI local del launcher: pruebas unitarias, de propiedades y de fuzzing (paquete F).
#   .\scripts\pruebas.ps1            perfil normal: semilla fija, menos de 60 s de pruebas
#   .\scripts\pruebas.ps1 -Largo     tanda larga: semilla nueva (impresa en el log), 25 veces mas iteraciones
#   .\scripts\pruebas.ps1 -Semilla N repite una tanda larga con la semilla impresa
# Va sin red (--offline). Las dependencias de prueba (kotest-property) deben estar en la cache de Gradle;
# la primera vez en un equipo nuevo, ejecutar una vez `gradlew.bat testDebugUnitTest` con red.
param([switch]$Largo, [long]$Semilla = 0)
$ErrorActionPreference = 'Stop'
$raiz = Split-Path -Parent $PSScriptRoot
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot' }
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:/jt'
New-Item -ItemType Directory -Force 'C:\jt' | Out-Null
$args2 = @('--offline', 'testDebugUnitTest')
if ($Largo) { $args2 += '-Plargo' }
if ($Semilla -ne 0) { $args2 += "-Pfsemilla=$Semilla" }
Push-Location $raiz
try {
    & .\gradlew.bat @args2
    exit $LASTEXITCODE
} finally { Pop-Location }
