# Verifies release build + R8/lint outputs.
# Usage: powershell -ExecutionPolicy Bypass -File .\scripts\verify-release.ps1

$ErrorActionPreference = "Stop"

Write-Host "Running release verification..."
.\gradlew.bat :app:assembleRelease :app:lintRelease --stacktrace

if (-not (Test-Path "app/build/outputs/mapping/release/mapping.txt")) {
    throw "Expected R8 mapping file was not generated."
}

Write-Host "Release verification passed."
