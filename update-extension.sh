#!/bin/bash

# Update ReManga extension in repository
# Usage: ./update-extension.sh

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${YELLOW}Updating ReManga extension...${NC}"

# Get APK from Desktop
SOURCE_APK="$HOME/Desktop/remanga-v2.apk"

if [ ! -f "$SOURCE_APK" ]; then
    echo -e "${RED}❌ APK not found at $SOURCE_APK${NC}"
    echo "Please build the extension first with: cd ~/Desktop/manga/remanga && ./rebuild.sh"
    exit 1
fi

# Get version from build.gradle
VERSION=$(grep "extVersionCode" "$HOME/Desktop/manga/remanga/src/ru/remanga/build.gradle" | sed 's/.*= //')

if [ -z "$VERSION" ]; then
    echo -e "${RED}❌ Could not read version from build.gradle${NC}"
    exit 1
fi

# Calculate version string (1.4.X format)
MAJOR=1
MINOR=4
PATCH=$VERSION
VERSION_STRING="$MAJOR.$MINOR.$PATCH"

echo -e "${YELLOW}Version: $VERSION_STRING (code: $VERSION)${NC}"

# Copy APK
NEW_APK="apk/ru.remanga-v$VERSION_STRING.apk"
cp "$SOURCE_APK" "$NEW_APK"
echo -e "${GREEN}✓ Copied APK to $NEW_APK${NC}"

# Update index.min.json
echo -e "${YELLOW}Updating index.min.json...${NC}"
cat > repo/index.min.json << INDEXEOF
[
  {
    "name": "ReManga",
    "pkg": "eu.kanade.tachiyomi.extension.ru.remanga",
    "apk": "ru.remanga-v$VERSION_STRING.apk",
    "lang": "ru",
    "code": $VERSION,
    "version": "$VERSION_STRING",
    "nsfw": false,
    "hasReadme": false,
    "hasChangelog": false,
    "sources": [
      {
        "id": "7584815203666942529",
        "lang": "ru",
        "name": "ReManga",
        "baseUrl": "https://remanga.org"
      }
    ]
  }
]
INDEXEOF
echo -e "${GREEN}✓ Updated index.min.json${NC}"

# Git commit and push
echo -e "${YELLOW}Committing changes...${NC}"
git add .
git commit -m "Update ReManga to v$VERSION_STRING"

echo -e "${YELLOW}Pushing to GitHub...${NC}"
git push

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  ✓ Extension Updated!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "Version: $VERSION_STRING"
echo "Users will see the update within 24 hours"
