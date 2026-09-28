#!/usr/bin/env bash
#
# release.sh — build and publish a QobuzDLX release in one step.
#
#   scripts/release.sh                 # release the version in build.gradle.kts
#   scripts/release.sh 1.9.3           # must match build.gradle.kts, or it aborts
#   scripts/release.sh --dry-run       # build and verify, stop before tagging
#
# Steps, each of which aborts on failure:
#   1. working tree clean, and level with origin/main
#   2. version in app/build.gradle.kts matches the tag being released
#   3. signing keystore present and readable
#   4. ./gradlew :app:assembleRelease
#   5. the APK's versionName and signature asserted
#   6. copied to dist/QobuzDLX-<version>-release.apk
#   7. annotated tag created and pushed
#   8. GitHub release created/updated with that exact APK
#
# Two deliberate differences from publish.sh, which this complements:
#   * it never passes --force to git push, and refuses to run when local main is
#     not level with origin/main. publish.sh force-pushes, so running it from a
#     stale checkout silently reverts main.
#   * it uploads the APK it just built and verified, rather than
#     `find dist -name "*-release.apk" | head -1`, which returns whichever file
#     the filesystem happens to list first and can publish an older version.
#
# publish.sh is still the one to use for the very first push, when the remote
# repository does not exist yet.
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRY_RUN=false
VERSION=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true ;;
    -h|--help) sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) printf 'unknown option: %s\n' "$arg" >&2; exit 2 ;;
    *)  VERSION="$arg" ;;
  esac
done

say() { printf '\n== %s\n' "$*"; }
ok()  { printf '   ok: %s\n' "$*"; }
die() { printf '\n!! %s\n' "$*" >&2; exit 1; }

OWNER="${QBdlxOwner:-imacncheese}"
REPO="${QBdlxRepo:-QobuzDownloaderX-Android}"

# --------------------------------------------------------------- toolchain
find_jdk() {
  [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ] && { printf '%s' "$JAVA_HOME"; return 0; }
  local c
  for c in "$HOME/android-dev/jdk-17" /mnt/f/dsh/tools/jdk/jdk-* "$HOME"/.jdks/*; do
    [ -x "$c/bin/java" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}
find_sdk() {
  [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/platforms" ] && { printf '%s' "$ANDROID_HOME"; return 0; }
  [ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "${ANDROID_SDK_ROOT}/platforms" ] && { printf '%s' "$ANDROID_SDK_ROOT"; return 0; }
  local c
  for c in "$HOME/android-dev/android-sdk" /mnt/f/dsh/tools/android-sdk; do
    [ -d "$c/platforms" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

JDK="$(find_jdk)" || die "no JDK found. Set JAVA_HOME."
SDK="$(find_sdk)" || die "no Android SDK found. Set ANDROID_HOME."
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$SDK/platform-tools:$PATH"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export GRADLE_OPTS="${GRADLE_OPTS:--Xmx6g -Dfile.encoding=UTF-8}"
ok "JDK $JDK"
ok "SDK $SDK"

# ----------------------------------------------------------------- version
GRADLE_FILE="app/build.gradle.kts"
[ -f "$GRADLE_FILE" ] || die "$GRADLE_FILE not found — run this from the repository root."

FILE_VERSION="$(sed -n 's/^ *versionName = "\(.*\)"$/\1/p' "$GRADLE_FILE" | head -1)"
FILE_CODE="$(sed -n 's/^ *versionCode = \([0-9][0-9]*\)$/\1/p' "$GRADLE_FILE" | head -1)"
[ -n "$FILE_VERSION" ] || die "could not read versionName from $GRADLE_FILE"

if [ -z "$VERSION" ]; then
  VERSION="$FILE_VERSION"
else
  [ "$VERSION" = "$FILE_VERSION" ] \
    || die "asked to release $VERSION but $GRADLE_FILE says $FILE_VERSION. Bump it and commit first."
fi

TAG="v$VERSION"
ASSET="QobuzDLX-$VERSION-release.apk"
say "releasing $TAG  (versionCode $FILE_CODE, asset $ASSET)"

# --------------------------------------------------------------------- git
say "checking git state"
[ -d .git ] || die "not a git repository."

if [ -n "$(git status --porcelain)" ]; then
  printf '%s\n' "$(git status --short)" >&2
  die "working tree is dirty. Commit or stash first."
fi
ok "working tree clean"

git fetch origin main --quiet || die "could not fetch origin/main."
LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main 2>/dev/null || true)"
[ -n "$REMOTE" ] || die "origin/main not found."
if [ "$LOCAL" != "$REMOTE" ]; then
  printf '   local  %s\n   origin %s\n' "$LOCAL" "$REMOTE" >&2
  die "local main is not level with origin/main. Push or pull first — this script never force-pushes."
fi
ok "level with origin/main at $(git rev-parse --short HEAD)"

# ---------------------------------------------------------------- keystore
say "signing key"
KS="${QBdlxKeyStorePath:-keystore/local.keystore}"
[ -f "$KS" ] || die "$KS is missing. Without it the release APK comes out unsigned and uninstallable."
"$JDK/bin/keytool" -list -keystore "$KS" -storepass "${QBdlxKeyStorePassword:-android}" 2>/dev/null \
  | grep -q PrivateKeyEntry || die "cannot read $KS with storepass '${QBdlxKeyStorePassword:-android}'."
ok "keystore readable"

# ------------------------------------------------------------------- build
say "building the release APK"
./gradlew --no-daemon :app:assembleRelease

APK="app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "no APK at $APK."

# ------------------------------------------------------------------ verify
say "verifying the APK"
BT="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
[ -x "$BT/aapt2" ] || die "aapt2 not found under $SDK/build-tools."

APK_VERSION="$("$BT/aapt2" dump badging "$APK" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
[ "$APK_VERSION" = "$VERSION" ] || die "APK reports versionName '$APK_VERSION', expected '$VERSION'. Stale build?"
ok "versionName $APK_VERSION"

"$BT/apksigner" verify "$APK" >/dev/null 2>&1 || die "APK is not signed."
CERT="$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/.*SHA-256 digest: //p' | head -1)"
ok "signed, cert SHA-256 $CERT"

mkdir -p dist
cp -f "$APK" "dist/$ASSET"
ok "dist/$ASSET"

if [ "$DRY_RUN" = true ]; then
  say "dry run — stopping before the tag and release"
  exit 0
fi

# --------------------------------------------------------------------- tag
say "tagging $TAG"
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  ok "tag already exists locally"
else
  git tag -a "$TAG" -m "QobuzDLX for Android $TAG"
  ok "created annotated tag"
fi
git push origin "refs/tags/$TAG" || die "could not push the tag."

# ----------------------------------------------------------------- release
say "publishing the GitHub release"

if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
  if gh release view "$TAG" -R "$OWNER/$REPO" >/dev/null 2>&1; then
    gh release upload "$TAG" "dist/$ASSET" --clobber -R "$OWNER/$REPO"
    gh release edit "$TAG" -R "$OWNER/$REPO" \
      --title "QobuzDLX for Android $TAG" --notes-file CHANGELOG.md
    ok "updated existing release"
  else
    gh release create "$TAG" "dist/$ASSET" -R "$OWNER/$REPO" \
      --title "QobuzDLX for Android $TAG" --notes-file CHANGELOG.md
    ok "created release"
  fi
  RELEASE_URL="https://github.com/$OWNER/$REPO/releases/tag/$TAG"
else
  TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
  [ -n "$TOKEN" ] || die "no gh login and no GITHUB_TOKEN/GH_TOKEN. Authenticate and retry."

  api() { curl -sS -H "Authorization: Bearer $TOKEN" -H "Accept: application/vnd.github+json" "$@"; }

  REL_ID="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" \
    | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"

  if [ -z "$REL_ID" ]; then
    BODY="$(python3 -c 'import json,sys; print(json.dumps({"tag_name":sys.argv[1],"name":"QobuzDLX for Android "+sys.argv[1],"body":open("CHANGELOG.md").read()}))' "$TAG")"
    api -X POST "https://api.github.com/repos/$OWNER/$REPO/releases" -d "$BODY" >/dev/null
    REL_ID="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" \
      | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"
    ok "created release"
  else
    ok "release already exists"
  fi
  [ -n "$REL_ID" ] || die "could not resolve the release id."

  OLD_ID="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/$REL_ID/assets" \
    | tr ',' '\n' | grep -B1 "\"name\": *\"$ASSET\"" \
    | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -1)"
  if [ -n "$OLD_ID" ]; then
    api -X DELETE "https://api.github.com/repos/$OWNER/$REPO/releases/assets/$OLD_ID" >/dev/null
    ok "removed the previous asset"
  fi

  curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary "@dist/$ASSET" \
    "https://uploads.github.com/repos/$OWNER/$REPO/releases/$REL_ID/assets?name=$ASSET" >/dev/null
  ok "uploaded $ASSET"
  RELEASE_URL="https://github.com/$OWNER/$REPO/releases/tag/$TAG"
fi

printf '\n------------------------------------------------------------\n'
printf 'Released %s\n  %s\n  signed by %s\n' "$TAG" "$RELEASE_URL" "$CERT"
