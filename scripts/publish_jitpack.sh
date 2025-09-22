#!/usr/bin/env bash
set -euo pipefail

# Publishes the Passage Kotlin SDK to JitPack.
#
# JitPack builds your library directly from GitHub releases/tags.
# No credentials or manual publishing needed - just create a git tag!
#
# Usage:
#   ./scripts/publish_jitpack.sh [VERSION]
#
# Example:
#   ./scripts/publish_jitpack.sh 1.0.0
#   ./scripts/publish_jitpack.sh  # Uses version from gradle.properties

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

cd "$REPO_ROOT"

# Get version from argument or gradle.properties
if [ $# -eq 1 ]; then
    VERSION="$1"
else
    VERSION=$(grep PUBLISHING_VERSION gradle.properties | cut -d'=' -f2)
fi

if [ -z "$VERSION" ]; then
    echo "Error: No version specified and couldn't find PUBLISHING_VERSION in gradle.properties"
    echo "Usage: $0 [VERSION]"
    exit 1
fi

# Ensure we're on a clean working tree
if [ -n "$(git status --porcelain)" ]; then
    echo "Error: Working directory is not clean. Please commit or stash your changes."
    exit 1
fi

# Get current branch
CURRENT_BRANCH=$(git branch --show-current)

echo "================================================"
echo "Publishing Passage Kotlin SDK to JitPack"
echo "================================================"
echo ""
echo "Version: $VERSION"
echo "Current branch: $CURRENT_BRANCH"
echo ""
echo "GitHub repository: github.com/tailriskai/passage-kotlin"
echo ""

# Check if tag already exists
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "Warning: Tag v$VERSION already exists!"
    read -p "Do you want to delete and recreate it? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        git tag -d "v$VERSION"
        git push origin --delete "v$VERSION" 2>/dev/null || true
    else
        echo "Aborted."
        exit 1
    fi
fi

echo "Creating git tag v$VERSION..."
git tag -a "v$VERSION" -m "Release version $VERSION"

echo "Pushing tag to GitHub..."
git push origin "v$VERSION"

echo ""
echo "✅ Successfully created and pushed tag v$VERSION!"
echo ""
echo "JitPack will automatically build your library from this tag."
echo ""
echo "To use this version in your Android project, add:"
echo ""
echo "1. Add JitPack repository to your root build.gradle.kts:"
echo "   repositories {"
echo "       google()"
echo "       mavenCentral()"
echo "       maven { url = uri(\"https://jitpack.io\") }"
echo "   }"
echo ""
echo "2. Add the dependency:"
echo "   implementation(\"com.github.tailriskai:passage-kotlin:v$VERSION\")"
echo ""
echo "Check build status at:"
echo "   https://jitpack.io/com/github/tailriskai/passage-kotlin/v$VERSION"
echo ""
echo "View all versions at:"
echo "   https://jitpack.io/#tailriskai/passage-kotlin"