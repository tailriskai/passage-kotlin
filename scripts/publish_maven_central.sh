#!/usr/bin/env bash
set -euo pipefail

# Publishes the Passage Kotlin SDK to Maven Central (Sonatype OSSRH).
#
# Required environment variables or Gradle properties:
#   OSSRH_USERNAME - Your Sonatype OSSRH username
#   OSSRH_PASSWORD - Your Sonatype OSSRH password
#   GPG_SIGNING_KEY - Your GPG private key (exported as ASCII-armored text)
#   GPG_SIGNING_PASSWORD - Password for your GPG key
#
# To export your GPG key:
#   gpg --armor --export-secret-keys YOUR_KEY_ID > private.key
#   export GPG_SIGNING_KEY=$(cat private.key)

: "${OSSRH_USERNAME:?Environment variable OSSRH_USERNAME must be set}"
: "${OSSRH_PASSWORD:?Environment variable OSSRH_PASSWORD must be set}"
: "${GPG_SIGNING_KEY:?Environment variable GPG_SIGNING_KEY must be set}"
: "${GPG_SIGNING_PASSWORD:?Environment variable GPG_SIGNING_PASSWORD must be set}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "$REPO_ROOT"

echo "Building and publishing Passage Kotlin SDK to Maven Central..."
echo "Group: ai.trypassage"
echo "Artifact: sdk"
echo "Version: $(grep PUBLISHING_VERSION gradle.properties | cut -d'=' -f2)"

# Ensure TTY for GPG
export GPG_TTY=$(tty)

./gradlew clean \
  :sdk:assembleRelease \
  :sdk:publishReleasePublicationToSonatypeRepository

echo ""
echo "✅ Successfully published to Sonatype OSSRH!"
echo ""
echo "Next steps:"
echo "1. Log in to https://s01.oss.sonatype.org/"
echo "2. Navigate to 'Staging Repositories'"
echo "3. Find your staging repository (ai.trypassage-XXXX)"
echo "4. Click 'Close' to trigger validation"
echo "5. After successful validation, click 'Release' to publish to Maven Central"
echo ""
echo "The artifacts will be available on Maven Central within 30 minutes to 2 hours."
echo ""
echo "Add to your Android project:"
echo "  implementation 'ai.trypassage:sdk:$(grep PUBLISHING_VERSION gradle.properties | cut -d'=' -f2)'"