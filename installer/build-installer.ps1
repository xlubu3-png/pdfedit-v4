<#
  Builds the Windows installer, installer\dist\WINTECH_PDF_Setup_<version>.exe, from the current source:
  bootJar -> jlink (a Java runtime holding only the modules the app needs) -> jpackage (+ WiX).

    .\installer\build-installer.ps1              # the Setup.exe
    .\installer\build-installer.ps1 -AppImageOnly  # a runnable folder, to try the app without installing

  Needs a JDK with jpackage on PATH and the WiX Toolset (for the .exe).
#>
param([switch]$AppImageOnly)
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $root 'backend'
$work = Join-Path $PSScriptRoot 'build'
$dist = Join-Path $PSScriptRoot 'dist'

# The app version ("0.035", see application.yml) is what people see: in the UI, the file name and the
# tray tooltip. Windows wants numeric major.minor.build, and an installer whose version is lower than
# the installed one is refused as a downgrade - the v1 installer was 1.0.1 - so Windows is told
# 1.0.<version * 1000>: 0.035 -> 1.0.35, 0.036 -> 1.0.36, 0.100 -> 1.0.100.
$yml = Get-Content (Join-Path $backend 'src\main\resources\application.yml') -Raw
$appVersion = [regex]::Match($yml, '(?m)^\s*version:\s*([0-9.]+)').Groups[1].Value
if (-not $appVersion) { throw 'app.version not found in application.yml' }
$build = [int]([decimal]::Parse($appVersion, [Globalization.CultureInfo]::InvariantCulture) * 1000)
$windowsVersion = "1.0.$build"
Write-Host "App version $appVersion -> Windows installer version $windowsVersion"

Write-Host '== 1/4 jar (with the frontend) =='
Push-Location $backend
try {
    & .\gradlew.bat bootJar --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'bootJar failed' }
} finally { Pop-Location }
$jar = Get-ChildItem (Join-Path $backend 'build\libs') -Filter '*.jar' | Sort-Object LastWriteTime -Descending | Select-Object -First 1

Write-Host '== 2/4 staging =='
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $work 'input') | Out-Null
Copy-Item $jar.FullName (Join-Path $work 'input\WINTECH_PDF.jar')

Write-Host '== 3/4 runtime (jlink) =='
$modules = 'java.base,java.compiler,java.datatransfer,java.desktop,java.instrument,java.logging,' +
    'java.management,java.management.rmi,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,' +
    'java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,' +
    'java.xml.crypto,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.management.agent,jdk.naming.dns,' +
    'jdk.unsupported,jdk.zipfs'
& jlink --add-modules $modules --output (Join-Path $work 'runtime') --strip-debug --no-header-files --no-man-pages --compress zip-6
if ($LASTEXITCODE -ne 0) { throw 'jlink failed' }

Write-Host '== 4/4 package (jpackage) =='
$wixBin = 'C:\Program Files\WiX Toolset v7.0\bin'
if ((Test-Path $wixBin) -and ($env:PATH -notlike "*$wixBin*")) { $env:PATH = "$wixBin;$env:PATH" }

$common = @(
    '--name', 'WINTECH_PDF',
    '--app-version', $windowsVersion,
    '--vendor', 'WINTECH',
    '--description', 'WINTECH PDF Editor',
    '--input', (Join-Path $work 'input'),
    '--main-jar', 'WINTECH_PDF.jar',
    '--main-class', 'org.springframework.boot.loader.launch.JarLauncher',
    '--runtime-image', (Join-Path $work 'runtime'),
    '--icon', (Join-Path $PSScriptRoot 'WINTECH_PDF.ico'),
    '--java-options', '-Dspring.profiles.active=installed',
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-XX:MaxRAMPercentage=50'
)

if ($AppImageOnly) {
    & jpackage --type app-image @common --dest (Join-Path $work 'app-image')
    if ($LASTEXITCODE -ne 0) { throw 'jpackage (app-image) failed' }
    Write-Host "Runnable folder: $work\app-image\WINTECH_PDF\WINTECH_PDF.exe"
    return
}

New-Item -ItemType Directory -Force -Path $dist | Out-Null
& jpackage --type exe @common --win-menu --win-menu-group WINTECH_PDF --win-shortcut --win-dir-chooser --dest $dist
if ($LASTEXITCODE -ne 0) { throw 'jpackage (exe) failed' }

$built = Get-ChildItem $dist -Filter 'WINTECH_PDF-*.exe' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$final = Join-Path $dist "WINTECH_PDF_Setup_$appVersion.exe"
if (Test-Path $final) { Remove-Item $final -Force }
Move-Item $built.FullName $final
Write-Host ('Installer: {0} ({1:N1} MB)' -f $final, ((Get-Item $final).Length / 1MB))
