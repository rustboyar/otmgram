#!/usr/bin/env bash
# Makes a release: scripts/release.sh 0.2.0
#
# 1. Checks: on main, nothing uncommitted, not behind origin, a newer version, notes under
#    "## Unreleased" in CHANGELOG.md; then scripts/check.sh (formatting, lint, unit tests).
# 2. Sets the version (gradle.properties) and dates the changelog's section.
# 3. Builds the release APK and checks it: signed with Otmgram's key, the right version.
# 4. Commits "Release 0.2.0" and tags v0.2.0; the APK and its SHA-256 go to app/build/dist.
# 5. Asks before pushing main and the tag and publishing the GitHub release (gh), whose
#    notes are the changelog's section. Answered no, it says how to do that later.
#
# Run the UI tests (scripts/ui-tests.sh) before, with a device. See docs/DEVELOPMENT.md.
set -euo pipefail
cd "$(dirname "$0")/.."
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# SHA-256 of the certificate releases are signed with (README.md shows it too). An APK
# signed with any other key couldn't update installed copies, so it's never published.
CERT_SHA256=25a01ad6bbf2c8801955a90748765dd8efc6ff8d520e98af7ffb92b216e0c53d

fail() { echo "release: $*" >&2; exit 1; }

version="${1:-}"
[[ "$version" =~ ^([0-9]+)\.([0-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])$ ]] || fail "usage: scripts/release.sh MAJOR.MINOR.PATCH (minor and patch below 100)"
code() { IFS=. read -r major minor patch <<<"$1"; echo $((major * 10000 + minor * 100 + patch)); }
current=$(sed -n 's/^otmgram.version=//p' gradle.properties)
[ "$(code "$version")" -gt "$(code "$current")" ] || fail "$version isn't newer than $current"
tag="v$version"

build_tools=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
command -v gh >/dev/null || fail "needs the GitHub CLI (gh)"
[ -x "$build_tools/apksigner" ] || fail "no apksigner in $build_tools"

[ "$(git branch --show-current)" = main ] || fail "not on main"
[ -z "$(git status --porcelain)" ] || fail "there are uncommitted changes"
git fetch -q origin
git merge-base --is-ancestor origin/main HEAD || fail "main is behind origin/main"
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "tag $tag exists"
git ls-remote --exit-code --tags origin "$tag" >/dev/null && fail "tag $tag exists on origin"

# The notes: what's between "## Unreleased" and the next section.
notes() { awk -v heading="$1" '$0 == heading { on = 1; next } on && /^## / { exit } on' CHANGELOG.md; }
[ -n "$(notes "## Unreleased" | tr -d '[:space:]')" ] || fail "nothing under \"## Unreleased\" in CHANGELOG.md"

scripts/check.sh

# From here on, a failure puts the two files back as they were.
committed=false
trap '$committed || git checkout -q -- gradle.properties CHANGELOG.md' EXIT
sed -i "s/^otmgram.version=.*/otmgram.version=$version/" gradle.properties
today=$(date +%Y-%m-%d)
sed -i "s/^## Unreleased\$/## Unreleased\n\n## $version - $today/" CHANGELOG.md

echo "Building $version…"
./gradlew -q :app:assembleRelease
apk=app/build/outputs/apk/release/app-release.apk
cert=$("$build_tools/apksigner" verify --print-certs "$apk" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p; s/^V2 Signer: certificate SHA-256 digest: //p' | head -1)
[ "$cert" = "$CERT_SHA256" ] || fail "the APK isn't signed with Otmgram's key (certificate $cert)"
built=$("$build_tools/aapt2" dump badging "$apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
[ "$built" = "$version" ] || fail "the APK says version $built"

dist=app/build/dist
mkdir -p "$dist"
cp "$apk" "$dist/otmgram-$version.apk"
(cd "$dist" && sha256sum "otmgram-$version.apk" >"otmgram-$version.apk.sha256")
{
    notes "## $version - $today"
    echo
    echo "APK SHA-256: \`$(cut -d' ' -f1 "$dist/otmgram-$version.apk.sha256")\`"
    echo "Signing certificate SHA-256: \`$CERT_SHA256\`"
} >"$dist/notes-$version.md"

git commit -q -m "Release $version" -- gradle.properties CHANGELOG.md
committed=true
git tag -a "$tag" -F "$dist/notes-$version.md" --cleanup=verbatim
echo "Committed and tagged $tag. APK: $dist/otmgram-$version.apk"

publish=(gh release create "$tag" "$dist/otmgram-$version.apk" "$dist/otmgram-$version.apk.sha256"
    --verify-tag --title "Otmgram $version" --notes-file "$dist/notes-$version.md")
read -r -p "Push main and $tag to origin and publish the GitHub release? [y/N] " answer || answer=
if [ "$answer" = y ]; then
    git push origin main "$tag"
    "${publish[@]}"
else
    echo "Not published. Later: git push origin main $tag && ${publish[*]}"
    echo "Or undo: git tag -d $tag && git reset --hard HEAD~1"
fi
