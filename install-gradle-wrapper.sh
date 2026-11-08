#!/bin/sh
# Download the official Gradle 8.13 wrapper JAR from the official Gradle GitHub tag.
# It must match the exact SHA-256 checksum before installation.
set -eu
cd "$(dirname "$0")"
destination='gradle/wrapper/gradle-wrapper.jar'
expected='81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else echo 'SHA-256 tool (sha256sum or shasum) not found' >&2; return 1; fi
}
if [ -f "$destination" ]; then
  [ "$(sha256_file "$destination")" = "$expected" ] && { echo 'Existing Gradle wrapper verified.'; exit 0; }
  echo 'Existing Gradle wrapper has an unexpected checksum. Aborting.' >&2
  exit 1
fi
command -v curl >/dev/null 2>&1 || { echo 'curl is required' >&2; exit 1; }
mkdir -p gradle/wrapper
tmp="${destination}.partial"
trap 'rm -f "$tmp"' EXIT HUP INT TERM
for url in \
  'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' \
  'https://github.com/gradle/gradle/raw/refs/tags/v8.13.0/gradle/wrapper/gradle-wrapper.jar'
do
  echo "Downloading official Gradle wrapper: $url"
  if curl -fLSs --connect-timeout 15 --max-time 90 "$url" -o "$tmp"; then
    actual=$(sha256_file "$tmp")
    if [ "$actual" = "$expected" ]; then
      mv "$tmp" "$destination"
      echo "Verified SHA-256: $actual"
      exit 0
    fi
    echo "Unexpected SHA-256: $actual; trying another official URL" >&2
  fi
done
echo 'Failed: unable to download and verify the official wrapper JAR.' >&2
exit 1
