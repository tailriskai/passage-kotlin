#!/usr/bin/env bash
set -euo pipefail

# Publishes the Passage Kotlin SDK AAR to the local Maven repository so other
# Android projects (including Capacitor bridges) can depend on it via
# mavenLocal().

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "$REPO_ROOT"

./gradlew clean :sdk:assembleRelease :sdk:publishReleasePublicationToMavenLocal
