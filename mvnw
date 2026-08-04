#!/usr/bin/env sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROPS="$BASE_DIR/.mvn/wrapper/maven-wrapper.properties"
CACHE_DIR="${MAVEN_WRAPPER_CACHE:-${HOME}/.m2/wrapper/dists}"

require_command() {
  command -v "$1" >/dev/null 2>&1 || { echo "Required command not found: $1" >&2; exit 127; }
}

require_command java
require_command curl
require_command tar
require_command sha512sum

JAVA_MAJOR=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p')
if [ "$JAVA_MAJOR" != "25" ]; then
  echo "InfraNexum requires JDK 25; detected ${JAVA_MAJOR:-unknown}." >&2
  exit 2
fi

URL=$(sed -n 's/^distributionUrl=//p' "$PROPS")
EXPECTED=$(sed -n 's/^distributionSha512Sum=//p' "$PROPS")
VERSION=$(printf '%s' "$URL" | sed -n 's#.*apache-maven-\([0-9.]*\)-bin.tar.gz#\1#p')
DEST="$CACHE_DIR/apache-maven-$VERSION"
ARCHIVE="$CACHE_DIR/apache-maven-$VERSION-bin.tar.gz"

mkdir -p "$CACHE_DIR"
if [ ! -x "$DEST/bin/mvn" ]; then
  rm -f "$ARCHIVE"
  curl --fail --location --proto '=https' --tlsv1.2 --retry 3 --output "$ARCHIVE" "$URL"
  ACTUAL=$(sha512sum "$ARCHIVE" | awk '{print $1}')
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    rm -f "$ARCHIVE"
    echo "Maven archive checksum mismatch." >&2
    exit 3
  fi
  rm -rf "$DEST"
  tar -xzf "$ARCHIVE" -C "$CACHE_DIR"
fi

exec "$DEST/bin/mvn" "$@"
