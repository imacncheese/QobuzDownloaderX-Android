#!/usr/bin/env bash
# Publishes QobuzDLX to GitHub: creates the repo, pushes main, tags a release and
# uploads the APK as a release asset.
#
# Authentication, in order of preference:
#   1. $GITHUB_TOKEN              (classic PAT with `repo` scope)
#   2. Git Credential Manager     (if you are already signed in)
#
# Usage:  GITHUB_TOKEN=... ./scripts/publish.sh
#         ./scripts/publish.sh --owner someoneelse --repo MyRepo --tag v1.0.5

set -euo pipefail

OWNER=""
REPO="QobuzDownloaderX-Android"
TAG="v1.0.6"
PRIVATE="false"

while [ $# -gt 0 ]; do
  case "$1" in
    --owner)   OWNER="$2"; shift 2 ;;
    --repo)    REPO="$2";  shift 2 ;;
    --tag)     TAG="$2";   shift 2 ;;
    --private) PRIVATE="true"; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

info() { printf '  %s\n' "$1"; }
ok()   { printf '  [ok] %s\n' "$1"; }
die()  { printf '  [x] %s\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- 1. git repo
[ -d .git ] || die "no git repository at $ROOT"
# Placeholder identity only; the owner is resolved from the credential later.
git config user.name  >/dev/null 2>&1 || git config user.name  "qbdlx-publisher"
git config user.email >/dev/null 2>&1 || git config user.email "qbdlx-publisher@users.noreply.github.com"
ok "git repository ready ($(git rev-parse --short HEAD))"

# ------------------------------------------------------------- 2. credentials
TOKEN="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
if [ -z "$TOKEN" ] && command -v git-credential-manager >/dev/null 2>&1; then
  TOKEN="$(printf 'protocol=https\nhost=github.com\n\n' \
    | git credential fill 2>/dev/null \
    | sed -n 's/^password=//p' | head -n1 || true)"
  [ -n "$TOKEN" ] && info "using a credential from Git Credential Manager"
fi
[ -n "$TOKEN" ] || die "no credential. Set GITHUB_TOKEN, or sign in with Git Credential Manager."
ok "credential found"

api() {
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" \
      -d "$body" "https://api.github.com$path"
  else
    curl -sS -X "$method" -H "Authorization: Bearer $TOKEN" \
      -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" \
      "https://api.github.com$path"
  fi
}

# ---------------------------------------------------------------- 3. who am I
ME="$(api GET /user | sed -n 's/.*"login": *"\([^"]*\)".*/\1/p' | head -n1)"
[ -n "$ME" ] || die "token rejected by GitHub"
ok "authenticated as $ME"

# Default the target owner to whoever the credential belongs to, so no personal
# account name is baked into the script.
if [ -z "$OWNER" ]; then
  OWNER="$ME"
  info "target owner defaulted to the authenticated user: $OWNER"
fi

echo
echo "Publishing $OWNER/$REPO"
echo "------------------------------------------------------------"

# ------------------------------------------------------------- 4. create repo
if api GET "/repos/$OWNER/$REPO" | grep -q '"full_name"'; then
  ok "repository already exists"
else
  info "creating repository..."
  api POST /user/repos "{\"name\":\"$REPO\",\"description\":\"Android client for downloading music from Qobuz, ported from QobuzDownloaderX (QBDLX).\",\"private\":$PRIVATE}" >/dev/null
  ok "created $OWNER/$REPO"
  sleep 3
fi

PUSH_URL="https://x-access-token:$TOKEN@github.com/$OWNER/$REPO.git"
git remote remove origin 2>/dev/null || true
git remote add origin "https://github.com/$OWNER/$REPO.git"

# ------------------------------------------------------------------- 5. push
info "pushing main..."
git push "$PUSH_URL" "HEAD:refs/heads/main" --force
ok "pushed main"

# -------------------------------------------------------------------- 6. tag
git tag -l "$TAG" | grep -q . || git tag -a "$TAG" -m "QobuzDLX for Android $TAG"
git push "$PUSH_URL" "refs/tags/$TAG"
ok "pushed tag $TAG"

# ---------------------------------------------------------------- 7. release
APK="$(find dist -name "*-release.apk" 2>/dev/null | head -n1 || true)"
[ -n "$APK" ] || die "no release APK found in dist/"
ok "release asset: $(basename "$APK")"

RELEASE_ID="$(api GET "/repos/$OWNER/$REPO/releases/tags/$TAG" \
  | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -n1)"

if [ -z "$RELEASE_ID" ]; then
  info "creating release $TAG..."
  api POST "/repos/$OWNER/$REPO/releases" \
    "$(printf '{"tag_name":"%s","name":"QobuzDLX for Android %s","body":%s}' \
        "$TAG" "$TAG" "$(python3 -c 'import json,sys;print(json.dumps(open("CHANGELOG.md").read()))' 2>/dev/null || echo '"See CHANGELOG.md."')")" >/dev/null
  RELEASE_ID="$(api GET "/repos/$OWNER/$REPO/releases/tags/$TAG" \
    | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -n1)"
  ok "created release"
fi

# Replace an existing asset with the same name.
OLD_ID="$(api GET "/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets" \
  | tr ',' '\n' | grep -B1 "$(basename "$APK")" | sed -n 's/.*"id": *\([0-9]*\).*/\1/p' | head -n1)"
[ -n "$OLD_ID" ] && api DELETE "/repos/$OWNER/$REPO/releases/assets/$OLD_ID" >/dev/null && info "removed previous asset"

curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" \
  "https://uploads.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets?name=$(basename "$APK")" >/dev/null
ok "uploaded $(basename "$APK")"

echo "------------------------------------------------------------"
echo "Done."
echo "  Repository: https://github.com/$OWNER/$REPO"
echo "  Release:    https://github.com/$OWNER/$REPO/releases/tag/$TAG"
echo
