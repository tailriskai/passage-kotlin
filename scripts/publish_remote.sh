#!/usr/bin/env bash
set -euo pipefail

# Publishes the Passage Kotlin SDK AAR to a remote Maven repository.
# Requires the following environment variables (or matching Gradle properties):
#   PASSAGE_MAVEN_URL
#   PASSAGE_MAVEN_USERNAME
#   PASSAGE_MAVEN_PASSWORD

: "${PASSAGE_MAVEN_URL:?Environment variable PASSAGE_MAVEN_URL must be set}"
: "${PASSAGE_MAVEN_USERNAME:?Environment variable PASSAGE_MAVEN_USERNAME must be set}"
: "${PASSAGE_MAVEN_PASSWORD:?Environment variable PASSAGE_MAVEN_PASSWORD must be set}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "$REPO_ROOT"

./gradlew clean \
  :sdk:assembleRelease \
  :sdk:publishReleasePublicationToPassageRepository
