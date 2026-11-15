# Getting DropSauce onto F-Droid

## Two versions of the app

| Version | Where | Google Drive sync | Signed by |
|---|---|---|---|
| GitHub (`github` flavor) | GitHub releases | yes (uses Google Play Services) | you |
| F-Droid (`fdroid` flavor) | F-Droid only | no, and no Google code at all | F-Droid |

Both are built from the same code and have the same version number. Drive-only code lives in
`app/src/github/`, the F-Droid stand-in in `app/src/fdroid/`.

GitHub releases only carry the GitHub version. F-Droid builds the `fdroid` flavor itself from each
`v*` tag (usually within a week), signs it with its own key and updates users through the F-Droid
app. Because the signatures differ, switching between the two versions needs an uninstall (make a
backup first).

`org.haziffe.dropsauce.yml` is the recipe F-Droid uses. The release workflow keeps its version in
sync with each release. The F-Droid version also uses its own copy of the Kotatsu backup source
list (`app/src/fdroid/res/raw/kotatsu_source_map.json`) without adult sources, since F-Droid only
accepts apps suitable for all ages. Regenerate that copy too if the main list ever changes.

## Submitting

1. Run the **Build and Release** workflow as usual.
2. Once, log in to GitLab: `glab auth login` (make a gitlab.com account first if needed).
3. Run `git pull && sh fdroid/submit-mr.sh`. It forks fdroiddata, adds the recipe for the
   latest release and opens the merge request with the text from `MR.md`.
4. Answer the reviewers on the merge request.

After it is merged, new versions reach F-Droid by themselves from the `v*` tags. The changelog
F-Droid shows comes from `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, which
the release workflow writes from the Unreleased section of CHANGELOG.md.

## Building locally

- `./gradlew installDebug` installs the GitHub version (debug).
- `./gradlew installFdroidDebug` installs the F-Droid version (debug) over it.
