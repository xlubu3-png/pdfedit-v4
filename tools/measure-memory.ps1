<#
  Measures the app's memory on Windows as the installed app runs it: the same Java runtime, program file and Java
  options as the installer gives the launcher (installer\build-installer.ps1). The screen is opened in Edge for a few
  seconds and closed, then the memory of every java.exe is written down every 10 seconds - "private" is what the
  Task Manager's Memory column shows.

  Run twice with -Aot: the first start has no AOT cache yet (as after an update) and makes it in a second process;
  the second start uses it.

    .\tools\measure-memory.ps1 -Java installer\build\runtime\bin\java.exe -Jar installer\build\input\WINTECH_PDF.jar
#>
param(
    [Parameter(Mandatory)][string]$Java,
    [Parameter(Mandatory)][string]$Jar,
    [string]$Cache = (Join-Path $env:TEMP 'wintech-measure\app.aot'),
    [int]$Seconds = 150,
    [string]$Label = 'run'
)
$ErrorActionPreference = 'Stop'
$port = 19190
$data = Join-Path $env:TEMP "wintech-measure-data-$Label"
if (Test-Path $data) { Remove-Item $data -Recurse -Force }
New-Item -ItemType Directory -Force $data, (Split-Path $Cache) | Out-Null

$options = @(
    '-Dspring.profiles.active=installed', '-Dfile.encoding=UTF-8', '-Xms32m', '-Xmx4g',
    "-XX:AOTCache=$Cache", '-XX:G1PeriodicGCInterval=60000', '-XX:MinHeapFreeRatio=10', '-XX:MaxHeapFreeRatio=30',
    '-XX:-G1PeriodicGCInvokesConcurrent', '--enable-native-access=ALL-UNNAMED',
    '-jar', (Resolve-Path $Jar).Path, "--server.port=$port"
)
$env:PDFEDIT_NO_BROWSER = 'true'
$env:PDFEDIT_DATA_DIR = $data
$app = Start-Process -FilePath (Resolve-Path $Java).Path -ArgumentList $options -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $data 'out.txt') -RedirectStandardError (Join-Path $data 'err.txt')
$started = Get-Date
while (-not (Test-NetConnection localhost -Port $port -InformationLevel Quiet -WarningAction SilentlyContinue)) {
    if ((Get-Date) - $started -gt [TimeSpan]::FromSeconds(90)) { throw 'the app did not start' }
    Start-Sleep -Milliseconds 500
}
Write-Host ("[{0}] started in {1:N1} s" -f $Label, ((Get-Date) - $started).TotalSeconds)
node (Join-Path $PSScriptRoot 'open-screen.cjs') "http://localhost:$port/" 8

function Show([int]$at) {
    $rows = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object {
        $p = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue
        if (-not $p) { return }
        $private = (Get-Counter "\Process(java*)\ID Process", "\Process(java*)\Working Set - Private" -ErrorAction SilentlyContinue).CounterSamples
        $mine = $private | Where-Object { $_.Path -like '*id process' -and $_.CookedValue -eq $_.ProcessId }
        $ws = 0
        if ($mine) {
            $instance = $mine.InstanceName
            $ws = ($private | Where-Object { $_.Path -like '*working set - private' -and $_.InstanceName -eq $instance }).CookedValue
        }
        $role = if ($_.ProcessId -eq $app.Id) { 'app' } elseif ($_.CommandLine -match 'aot-training') { 'aot-training' } else { 'other' }
        "{0}={1:N0}MB(ws {2:N0}MB)" -f $role, ($ws / 1MB), ($p.WorkingSet64 / 1MB)
    }
    Write-Host ("[{0}] t={1,3}s  {2}" -f $Label, $at, ($rows -join '  '))
}
for ($t = 10; $t -le $Seconds; $t += 10) {
    Start-Sleep -Seconds 10
    Show $t
}
Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Get-Content (Join-Path $data 'out.txt') -ErrorAction SilentlyContinue | Select-String 'AOT|글꼴|Started|엔진' | ForEach-Object { Write-Host "[$Label] log: $_" }
