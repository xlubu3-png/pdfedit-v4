<#
  Publishes the installer of the current version as a GitHub release, which is where installed copies of the
  app look for updates:

    .\installer\build-installer.ps1      # builds installer\dist\WINTECH_PDF_Setup_<version>.exe
    .\installer\publish-release.ps1      # tag v<version>, uploads the installer and its SHA-256

  The release says what changed in Korean, because the app shows it in its update notice. The text is taken from
  installer\release-notes\<version>.md (written and committed together with the version's change), or from
  -Notes "..."; text without Korean is refused. The release is titled "WINTECH 문서편집기 v<version>".

  The app downloads only the file named WINTECH_PDF_Setup_*.exe from the release and refuses it unless it
  matches the published hash (GitHub's own digest of the asset, or the .sha256 file uploaded here).

  Needs the GitHub CLI (gh), logged in (gh auth login) with rights on the repository, and the commit of this
  version pushed. -Draft creates the release unpublished.

  Saved as UTF-8 with a BOM: Windows PowerShell 5.1 reads the Korean text in this file correctly only then.
#>
param([string]$Notes, [switch]$Draft)
$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$yml = Get-Content (Join-Path $root 'backend\src\main\resources\application.yml') -Raw
$version = [regex]::Match($yml, '(?m)^\s*version:\s*"?([0-9.]+)').Groups[1].Value
if (-not $version) { throw 'application.yml에서 app.version을 찾지 못했습니다.' }
$tag = "v$version"

$installer = Join-Path $PSScriptRoot "dist\WINTECH_PDF_Setup_$version.exe"
if (-not (Test-Path $installer)) {
    throw "설치 파일이 없습니다: $installer`n먼저 .\installer\build-installer.ps1 을 실행하세요."
}

# What the release says. The app shows its first lines in the update notice, so it must be Korean.
$notesFile = Join-Path $PSScriptRoot "release-notes\$version.md"
if (-not $Notes) {
    if (-not (Test-Path $notesFile)) {
        throw "릴리스 설명이 없습니다. $notesFile 에 이번 버전에서 바뀐 점을 한글로 써서 커밋하거나, -Notes 로 주세요."
    }
    $Notes = Get-Content $notesFile -Raw -Encoding UTF8
}
$Notes = "$Notes".Trim()  # an empty file reads as $null
if ($Notes -notmatch '[가-힣]') {
    throw '릴리스 설명에 한글이 없습니다. 앱의 업데이트 안내에 그대로 보이므로 한글로 써 주세요.'
}

Push-Location $root
try {
    # git and gh write ordinary messages ("release not found", progress) to stderr, which PowerShell would
    # turn into a terminating error: judge them by their exit codes instead.
    $ErrorActionPreference = 'Continue'
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw 'GitHub CLI(gh)가 없습니다. https://cli.github.com 에서 설치한 뒤 gh auth login 으로 로그인하세요.'
    }
    gh auth status *> $null
    if ($LASTEXITCODE -ne 0) { throw 'GitHub CLI(gh)에 로그인되어 있지 않습니다. 먼저 gh auth login 을 실행하세요.' }
    if (git status --porcelain) { throw '커밋하지 않은 변경이 있습니다. 릴리스가 소스와 같도록 먼저 커밋하세요.' }
    git fetch origin *> $null
    if ((git rev-parse HEAD) -ne (git rev-parse '@{u}')) {
        throw '마지막 커밋이 아직 GitHub에 올라가지 않았습니다. git push 를 먼저 하세요 (태그가 GitHub에 없는 코드를 가리키게 됩니다).'
    }
    gh release view $tag *> $null
    if ($LASTEXITCODE -eq 0) { throw "릴리스 $tag 이(가) 이미 있습니다. 새로 내려면 app.version을 올리세요." }

    # "<hash>  <file name>", the format sha256sum writes.
    $hash = (Get-FileHash $installer -Algorithm SHA256).Hash.ToLowerInvariant()
    $sumsFile = "$installer.sha256"
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($sumsFile, "$hash  $(Split-Path $installer -Leaf)`n", $utf8)

    # Handed to gh as a file: Windows PowerShell 5.1 breaks quotes inside arguments to programs, and notes have them.
    $notesOut = Join-Path $PSScriptRoot "dist\release-notes-$version.md"
    [IO.File]::WriteAllText($notesOut, "$Notes`n", $utf8)

    $ghArgs = @('release', 'create', $tag, $installer, $sumsFile, '--title', "WINTECH 문서편집기 v$version",
        '--notes-file', $notesOut, '--target', (git rev-parse HEAD))
    if ($Draft) { $ghArgs += '--draft' }
    & gh @ghArgs
    if ($LASTEXITCODE -ne 0) { throw 'GitHub 릴리스를 만들지 못했습니다 (gh release create). 위에 나온 메시지를 확인하세요.' }
    Write-Host "릴리스 $tag 을(를) 올렸습니다: $(Split-Path $installer -Leaf) (SHA-256 $hash)"
} finally { Pop-Location }
