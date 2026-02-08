# Quick build script - Sets Java 17 and runs gradlew
# Usage: .\build.ps1 [gradle-arguments]
# Example: .\build.ps1 build
# Example: .\build.ps1 assembleDebug

$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# Run gradlew with all passed arguments
.\gradlew.bat @args
