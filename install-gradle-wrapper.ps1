$ErrorActionPreference = 'Stop'

# Gradle 8.13's source tag is "v8.13.0" (not "v8.13").
# Official checksum: https://gradle.org/release-checksums/
# This script is intentionally compatible with Windows PowerShell 5.1.
$expected = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
$urls = @(
    'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar',
    'https://github.com/gradle/gradle/raw/refs/tags/v8.13.0/gradle/wrapper/gradle-wrapper.jar'
)
$dest = Join-Path $PSScriptRoot 'gradle\wrapper\gradle-wrapper.jar'
$parent = Split-Path -Parent $dest
if (-not (Test-Path -LiteralPath $parent)) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}

if (Test-Path -LiteralPath $dest) {
    $current = (Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($current -eq $expected) {
        Write-Host 'Gradle 8.13 wrapper already installed; SHA-256 verified.'
        exit 0
    }
    throw "Existing gradle-wrapper.jar has an unexpected SHA-256. Refusing to overwrite: $dest"
}

# Force TLS 1.2 for older PowerShell 5.1 configurations.
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$temp = "$dest.partial"
try {
    $downloaded = $false
    foreach ($url in $urls) {
        try {
            Write-Host "Downloading Gradle 8.13 wrapper from: $url"
            if (Test-Path -LiteralPath $temp) {
                Remove-Item -LiteralPath $temp -Force
            }
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $temp
            $actual = (Get-FileHash -LiteralPath $temp -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($actual -ne $expected) {
                throw "SHA-256 mismatch: expected $expected; received $actual"
            }
            Move-Item -LiteralPath $temp -Destination $dest
            $downloaded = $true
            Write-Host "SHA-256 verified: $actual"
            Write-Host "Installed wrapper: $dest"
            break
        } catch {
            Write-Warning "This source failed: $($_.Exception.Message)"
        }
    }
    if (-not $downloaded) {
        throw 'Could not download and verify Gradle 8.13 wrapper from either official GitHub URL. No unverified file was installed.'
    }
} finally {
    if (Test-Path -LiteralPath $temp) {
        Remove-Item -LiteralPath $temp -Force
    }
}
