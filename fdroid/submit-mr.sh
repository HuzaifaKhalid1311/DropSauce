#!/bin/sh
# Opens the "New app: DropSauce" merge request on F-Droid for the latest release.
# Run after the release workflow has finished:  git pull && sh fdroid/submit-mr.sh
set -eu
cd "$(dirname "$0")/.."
REPO=$(pwd)

glab auth status >/dev/null 2>&1 || { echo "Log in to GitLab first: glab auth login"; exit 1; }

git fetch -q --tags origin
TAG=$(git tag --list 'v*' --sort=-v:refname | head -n1)
HASH=$(git rev-parse "$TAG^{commit}")
RECIPE=$(git show "$TAG:fdroid/org.haziffe.dropsauce.yml" 2>/dev/null) || { echo "$TAG has no F-Droid recipe; make a new release first"; exit 1; }
echo "Submitting $TAG ($HASH)"

USER_NAME=$(glab api user | python3 -c 'import json,sys; print(json.load(sys.stdin)["username"])')
FORK="$USER_NAME/fdroiddata"
glab repo view "$FORK" >/dev/null 2>&1 || glab repo fork fdroid/fdroiddata --clone=false
# GitLab copies a fork in the background; wait until it has its files.
until glab api "projects/$USER_NAME%2Ffdroiddata/repository/branches/master" >/dev/null 2>&1; do sleep 10; done

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git clone -q --depth 1 https://gitlab.com/fdroid/fdroiddata.git "$WORK"
cd "$WORK"
git checkout -q -b org.haziffe.dropsauce
# F-Droid wants the full commit hash, not the tag.
printf '%s\n' "$RECIPE" | sed -E "s/^(    commit: ).*/\1$HASH/" > metadata/org.haziffe.dropsauce.yml
git add metadata/org.haziffe.dropsauce.yml
git -c user.name="$(git -C "$REPO" config user.name)" -c user.email="$(git -C "$REPO" config user.email)" \
	commit -q -m "New app: DropSauce"
git -c credential.helper= -c 'credential.helper=!glab auth git-credential' \
	push -q "https://gitlab.com/$FORK.git" org.haziffe.dropsauce

glab mr create -R fdroid/fdroiddata --head "$FORK" -s org.haziffe.dropsauce -b master \
	-t "New app: DropSauce" --description-file "$REPO/fdroid/MR.md" --allow-collaboration=true --yes
