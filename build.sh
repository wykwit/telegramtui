#!/usr/bin/env bash
# TelegramTUI build script: compiles, runs tests, and bundles a runnable fat .jar
# Usage: ./build.sh [extra mvn args...]
#   ./build.sh                  # clean + package (tests run)
#   ./build.sh -DskipTests      # bundle without running tests

set -e

say()  { printf '\033[1;32m==> \033[0m%s\n' "$*" >&2; }
warn() { printf '\033[1;33mwarn:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

# resolve the project root from this script's location, so it runs from anywhere
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

[ -f pom.xml ] || die "pom.xml not found in $SCRIPT_DIR — run this from the project root."
command -v mvn >/dev/null 2>&1 || die "Maven (mvn) not found on PATH. Install it first (e.g. 'sudo apt install maven')."

# read artifactId/version straight from the pom so this stays in sync
read_xml() { grep -m1 -o "<$1>[^<]*</$1>" pom.xml | sed -E "s/<\/?$1>//g" | tr -d '[:space:]'; }
ARTIFACT="$(read_xml artifactId)"
VERSION="$(read_xml version)"
[ -n "$ARTIFACT" ] && [ -n "$VERSION" ] || die "Could not read artifactId/version from pom.xml."

say "Building $ARTIFACT $VERSION (clean + package)..."
mvn clean package "$@"

JAR="target/$ARTIFACT-$VERSION.jar"
[ -f "$JAR" ] || die "Expected bundle not found: $JAR"

SIZE="$(du -h "$JAR" | cut -f1)"
say "Built $JAR ($SIZE)"
say "Run with: java -jar $JAR"
