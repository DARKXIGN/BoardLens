# Downloads the Tossface SVGs listed in manifest.txt from Toss's GitHub repository into
# app/src/main/assets/tossface and checks each file's SHA-256, so the app ships the
# original files unmodified (Tossface license: no modified versions, format changes included).
#
# Run from anywhere:
#   powershell -ExecutionPolicy Bypass -File tools\tossface\fetch_tossface.ps1
# To add an icon: append "<file> <sha256>" to manifest.txt (file names as in dist/svg, e.g. u1F4CB).

$ErrorActionPreference = 'Stop'
$commit = '37720aa5cf2ec9a853a9787f29e39002c58cc2e7'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$dest = Join-Path $repoRoot 'app\src\main\assets\tossface'
New-Item -ItemType Directory -Force -Path $dest | Out-Null

$ok = 0
$bad = @()
Get-Content (Join-Path $PSScriptRoot 'manifest.txt') |
    Where-Object { $_.Trim() -and -not $_.StartsWith('#') } |
    ForEach-Object {
        $name, $expected = $_.Trim() -split '\s+'
        $out = Join-Path $dest "$name.svg"
        $url = "https://raw.githubusercontent.com/toss/tossface/$commit/dist/svg/$name.svg"
        Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $out
        $actual = (Get-FileHash -Algorithm SHA256 -Path $out).Hash.ToLower()
        if ($actual -eq $expected) { $ok++ } else { $bad += $name }
    }

Write-Host "$ok icon(s) downloaded and verified."
if ($bad.Count -gt 0) {
    Write-Host ("Hash mismatch: " + ($bad -join ', ')) -ForegroundColor Red
    exit 1
}
