#!/usr/bin/env bash
set -euo pipefail

echo "Running release verification..."
./gradlew :app:assembleRelease :app:lintRelease --stacktrace

if [[ ! -f "app/build/outputs/mapping/release/mapping.txt" ]]; then
  echo "Expected R8 mapping file was not generated."
  exit 1
fi

echo "Release verification passed."
