<#
  Publishes the installer of the current version as a GitHub release, which is where installed copies of the
  app look for updates:

    .\installer\build-installer.ps1      # builds installer\dist\WINTECH_PDF_Setup_<version>.exe
    .\installer\publish-release.ps1      # tag v<version>, uploads the installer and its SHA-256

  The app downloads only the file named WINTECH_PDF_Setup_*.exe from the release and refuses it unless it
  matches the published hash (GitHub's own digest of the asset, or the .sha256 file uploaded here).

  Needs the GitHub CLI (gh), logged in with rights on the repository, and the commit of this version pushed.
  -Notes "text"  what the release says (defaults to the last commit message); -Draft  creates it unpublished.
#>
param([string]$Notes, [switch]$Draft)
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$yml = Get-Content (Join-Path $root 'backend\src\main\resources\application.yml') -Raw
$version = [regex]::Match($yml, '(?m)^\s*version:\s*"?([0-9.]+)').Groups[1].Value
if (-not $version) { throw 'app.version not found in application.yml' }
$tag = "v$version"

$installer = Join-Path $PSScriptRoot "dist\WINTECH_PDF_Setup_$version.exe"
if (-not (Test-Path $installer)) { throw "Build the installer first: $installer does not exist (run installer\build-installer.ps1)." }

Push-Location $root
try {
    if (git status --porcelain) { throw 'There are uncommitted changes: commit them first, so the release matches the source.' }
    git fetch origin 2>$null
    if ((git rev-parse HEAD) -ne (git rev-parse '@{u}')) { throw 'The latest commit is not pushed yet (git push), so the tag would point at code that is not on GitHub.' }
    if (gh release view $tag 2>$null) { throw "Release $tag already exists. Raise app.version for a new release." }

    # "<hash>  <file name>", the format sha256sum writes.
    $hash = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    $sumsFile = "$installer.sha256"
    [IO.File]::WriteAllText($sumsFile, "$hash  $(Split-Path $installer -Leaf)`n", (New-Object Text.UTF8Encoding($false)))

    if (-not $Notes) { $Notes = (git log -1 --format=%B).Trim() }
    $args = @('release', 'create', $tag, $installer, $sumsFile, '--title', "WINTECH_PDF v$version", '--notes', $Notes, '--target', (git rev-parse HEAD))
    if ($Draft) { $args += '--draft' }
    & gh @args
    if ($LASTEXITCODE -ne 0) { throw 'gh release create failed' }
    Write-Host "Published $tag with $(Split-Path $installer -Leaf) (SHA-256 $hash)"
} finally { Pop-Location }
