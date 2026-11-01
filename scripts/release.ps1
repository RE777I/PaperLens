<#
.SYNOPSIS
  Publishes a PaperLens release on GitHub: signed APKs for each phone type, a git tag and release notes.

.DESCRIPTION
  1. Stops if there are uncommitted changes (a release must match a commit).
  2. Sets versionName to -Version in app/build.gradle.kts and raises versionCode by one (unless this
     version is already set, e.g. the first release), and commits that.
  3. Builds the signed release APKs (signing settings in local.properties; see README "Signing").
  4. Tags the commit v<Version>, pushes main and the tag.
  5. Creates the GitHub release "PaperLens <Version>" with the APKs attached
     (PaperLens-<Version>-arm64-v8a.apk is the one for almost every phone).

.EXAMPLE
  .\scripts\release.ps1 -Version 1.1.0 -Notes "Faster Find Text; Chinese translation fixes"

.NOTES
  Needs: git, the GitHub CLI (gh auth login), JDK 17 at %USERPROFILE%\tools\jdk17.
  Releases are listed at https://github.com/<you>/PaperLens/releases.
#>
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (git status --porcelain) { throw "Commit or discard your changes first: a release must match a commit." }
if (git tag --list "v$Version") { throw "Version $Version is already released (tag v$Version exists)." }

# Version numbers in app/build.gradle.kts
$gradle = "app/build.gradle.kts"
$text = Get-Content $gradle -Raw
$currentName = [regex]::Match($text, 'versionName = "([^"]+)"').Groups[1].Value
$currentCode = [int][regex]::Match($text, 'versionCode = (\d+)').Groups[1].Value
if ($currentName -ne $Version) {
    $text = $text -replace 'versionName = "[^"]+"', "versionName = `"$Version`""
    $text = $text -replace 'versionCode = \d+', "versionCode = $($currentCode + 1)"
    Set-Content $gradle $text -NoNewline
    git add $gradle
    git commit -m "Release $Version"
}

# Signed release build
$env:JAVA_HOME = "$env:USERPROFILE\tools\jdk17"
.\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) { throw "The release build failed." }

$out = Join-Path $root "release"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $out -Filter *.apk | Remove-Item
$apks = foreach ($abi in "arm64-v8a", "armeabi-v7a", "x86_64") {
    $src = "app/build/outputs/apk/release/app-$abi-release.apk"
    $dst = Join-Path $out "PaperLens-$Version-$abi.apk"
    Copy-Item $src $dst
    $dst
}

git tag -a "v$Version" -m "PaperLens $Version"
git push origin main
git push origin "v$Version"

$body = @"
$Notes

**Which file?** Almost every phone: ``PaperLens-$Version-arm64-v8a.apk``. Older 32-bit phones: ``armeabi-v7a``. Emulators and some tablets: ``x86_64``.

The page-corner model (83 MB) downloads on first use over Wi-Fi, or from Me → Scan → Page detection model.
"@
gh release create "v$Version" @apks --title "PaperLens $Version" --notes $body
if ($LASTEXITCODE -ne 0) { throw "Creating the GitHub release failed (is 'gh auth login' done?)." }
Write-Host "Released PaperLens $Version"
