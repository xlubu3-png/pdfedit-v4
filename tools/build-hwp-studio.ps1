<#
  Builds rhwp-studio (the HWP/HWPX viewer and editor of https://github.com/edwardkim/rhwp, MIT) so that this
  app serves it itself at /hwp-studio/: no part of it, and no document, goes through an outside server.

    .\tools\build-hwp-studio.ps1               # the version below
    .\tools\build-hwp-studio.ps1 -Version 0.8.8

  What it does:
    1. takes the studio's source, its bundled open-source fonts and its licences from the rhwp tag v<Version>
    2. takes the matching prebuilt WASM engine from the npm package @rhwp/core@<Version> (no Rust needed)
    3. builds the studio with the base path /hwp-studio/, without the HwpCtrl plugin and with the outside
       (CDN) web fonts switched off; the bundled fonts are used instead
    4. leaves out its sample documents and its service worker (the app serves the files itself; a cached
       copy would outlive an update of the app)
    5. puts the result in frontend\public\hwp-studio, which the frontend build copies into the app

  Needs git, node/npm and network access while it runs; the app built from the result needs neither.
#>
param([string]$Version = '0.8.7')
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$target = Join-Path $root 'frontend\public\hwp-studio'
$work = Join-Path ([IO.Path]::GetTempPath()) "rhwp-build-$Version"

function Run([string]$what, [scriptblock]$command) {
    Write-Host "== $what =="
    # git and npm print ordinary progress on stderr: judge them by their exit code.
    $ErrorActionPreference = 'Continue'
    & $command
    $code = $LASTEXITCODE
    $ErrorActionPreference = 'Stop'
    if ($code -ne 0) { throw "$what failed (exit $code)" }
}

if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory $work | Out-Null
$src = Join-Path $work 'rhwp'

Run 'source' { git clone --quiet --depth 1 --branch "v$Version" --filter=blob:none --sparse https://github.com/edwardkim/rhwp.git $src }
Push-Location $src
try {
    Run 'sparse checkout' { git sparse-checkout set rhwp-studio npm assets/fonts }
} finally { Pop-Location }

Push-Location $work
try {
    Run 'engine' { npm pack --silent "@rhwp/core@$Version" }
} finally { Pop-Location }
$pkg = Join-Path $src 'pkg'
New-Item -ItemType Directory $pkg | Out-Null
tar -xzf (Join-Path $work "rhwp-core-$Version.tgz") -C $work
Copy-Item (Join-Path $work 'package\*') $pkg -Recurse

$studio = Join-Path $src 'rhwp-studio'
# public\fonts is a link to ..\..\assets\fonts in the repository; Windows checks it out as a small text file.
$fontsLink = Join-Path $studio 'public\fonts'
if (Test-Path $fontsLink -PathType Leaf) { Remove-Item $fontsLink -Force }
Copy-Item (Join-Path $src 'assets\fonts') $fontsLink -Recurse

Push-Location $studio
try {
    Run 'dependencies' { npm ci --ignore-scripts --no-audit --no-fund }
    $env:RHWP_WITHOUT_HWPCTRL = '1'
    $env:RHWP_DISABLE_EXTERNAL_WEBFONTS = '1'
    Run 'type check' { npx tsc }
    Run 'build' { npx vite build --base /hwp-studio/ }
} finally {
    Remove-Item Env:RHWP_WITHOUT_HWPCTRL, Env:RHWP_DISABLE_EXTERNAL_WEBFONTS -ErrorAction SilentlyContinue
    Pop-Location
}

$dist = Join-Path $studio 'dist'
foreach ($name in 'samples', 'sw.js') {
    $path = Join-Path $dist $name
    if (Test-Path $path) { Remove-Item $path -Recurse -Force }
}
Get-ChildItem $dist -Filter 'workbox-*.js' | Remove-Item -Force
# The page still loads registerSW.js: it now takes away a service worker an earlier copy may have left.
[IO.File]::WriteAllText((Join-Path $dist 'registerSW.js'),
    "if ('serviceWorker' in navigator) { navigator.serviceWorker.getRegistrations().then(rs => rs.forEach(r => { if (r.scope.includes('/hwp-studio/')) r.unregister() })) }`n")

Copy-Item (Join-Path $src 'LICENSE') (Join-Path $dist 'LICENSE-rhwp.txt')
Copy-Item (Join-Path $src 'THIRD_PARTY_LICENSES.md') (Join-Path $dist 'THIRD_PARTY_LICENSES.md')
[IO.File]::WriteAllText((Join-Path $dist 'VERSION.txt'), "rhwp-studio $Version (https://github.com/edwardkim/rhwp/tree/v$Version), engine @rhwp/core@$Version`n")

if (Test-Path $target) { Remove-Item $target -Recurse -Force }
Copy-Item $dist $target -Recurse
$size = (Get-ChildItem $target -Recurse -File | Measure-Object Length -Sum).Sum / 1MB
Write-Host ("rhwp-studio {0} -> {1} ({2:N1} MB)" -f $Version, $target, $size)
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
