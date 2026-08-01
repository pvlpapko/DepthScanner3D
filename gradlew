#!/usr/bin/env sh
set -eu

GRADLE_VERSION="8.13"
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/depthscanner-wrapper"
DIST_DIR="$CACHE_ROOT/gradle-$GRADLE_VERSION"
ZIP_FILE="$CACHE_ROOT/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_BIN="$DIST_DIR/bin/gradle"

if [ ! -x "$GRADLE_BIN" ]; then
  mkdir -p "$CACHE_ROOT"
  if [ ! -f "$ZIP_FILE" ]; then
    URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    echo "Downloading Gradle $GRADLE_VERSION..."
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP_FILE"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP_FILE" "$URL"
    else
      echo "curl or wget is required to download Gradle." >&2
      exit 1
    fi
  fi
  rm -rf "$DIST_DIR"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ZIP_FILE" -d "$CACHE_ROOT"
  else
    echo "unzip is required to unpack Gradle." >&2
    exit 1
  fi
fi

exec "$GRADLE_BIN" -p "$BASE_DIR" "$@"
