## Abstract for new app inclusion

### Please introduce the functions and usage of your app in summary, and also attach some screenshots.

DropSauce is a comic and novel reader with a Material 3 Expressive interface. It ships with no content and no sources: like Mihon, the user adds extension repositories themselves, and the app runs Mihon/Tsundoku extensions and LNReader JS plugins from them. It also reads local CBZ, EPUB and PDF files.

Features: manga, webtoon and novel readers, library with categories, history, bookmarks and an updates feed, offline downloads, tracking (AniList, MyAnimeList, Kitsu, Shikimori, MangaBaka), local backups and restoring Mihon/Kotatsu backups, widgets, app lock and incognito mode.

Screenshots: https://github.com/HuzaifaKhalid1311/DropSauce/tree/main/fastlane/metadata/android/en-US/images/phoneScreenshots

### Please elaborate why you wrote this app. Please highlight the unique functions it provides over other similar apps in F-Droid.

It is a Kotatsu fork that reads both comics and novels in one app: it runs Mihon extensions and LNReader plugins side by side, which no reader on F-Droid does today. Kotatsu itself was archived on F-Droid because it shipped built-in sources; DropSauce ships none.

## Notes for reviewers

- **Flavors:** the recipe builds the `fdroid` flavor. It has no Google Play Services (the GitHub flavor's Google Drive sync is left out) and no junrar (non-free licence; real RAR archives are not supported in this flavor).
- **No updater:** the in-app updater is disabled in the `fdroid` flavor.
- **Third-party add-ons:** nothing is bundled or preselected. Adding a repository shows a notice that its extensions are made by others and not checked by F-Droid.
- **All ages:** no sources ship with the app. The table used to import old Kotatsu backups has every adult-rated source removed in the `fdroid` flavor.
- **Native code:** the JPEG XL decoder (libjxl, BSD-3-Clause) is built from source through Gradle/CMake from a git submodule; the recipe pins the NDK and deletes libjxl's test data with `scandelete`.
- **Reproducible builds:** not enabled. The F-Droid flavor is a different APK from the one published on GitHub, and only the GitHub one is uploaded there, so there is no upstream APK to compare against. F-Droid's signature is fine.

## Checklist

### Policy

* [x] The app complies with the [inclusion policy](https://f-droid.org/docs/Inclusion_Policy). _Pay special attention to Quality point 6._
* [x] The original app author has been notified (and does not oppose the inclusion). I am the author.
* [x] The upstream app source code repo contains the app metadata in a [Fastlane](https://gitlab.com/snippets/1895688) or [Triple-T](https://gitlab.com/snippets/1901490) folder structure. The summary and description must be included and images, icon, and changelog should also be provided for better user experience. The `en-US` locale must be included.

### Docs

* [x] Please read [the guide](https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md) first if this is your first contribution.
* [x] Please make sure your metadata follows the best practice in [our templates](https://gitlab.com/fdroid/fdroiddata/tree/master/templates).
* [x] Please read the [Build Metadata Reference](https://f-droid.org/docs/Build_Metadata_Reference/) and make sure your metadata is valid.
* [x] Please read the [Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/).

### Merge Request Setup

* [x] The title of this merge request should follow "New app: app name" format.
* [x] Please make sure your fdroiddata fork is public and your branch is not protected.
* [x] Please read [our Git guide](https://gitlab.com/fdroid/wiki/-/wikis/Tips-for-fdroiddata-contributors/Git-Usage) if you don't know how to rebase your branch. Don't rebase your branch if there is no conflict.
* [x] All related [fdroiddata](https://gitlab.com/fdroid/fdroiddata/issues) and [RFP issues](https://gitlab.com/fdroid/rfp/issues) have been referenced in this merge request (there are none).
* [x] Please only submit one app in one MR.

### Metadata

* [x] Metadata must be put in `metadata/<applicationId>.yml`.
* [x] Metadata must be a valid YAML file.
* [x] Metadata must use LF as line ending.
* [x] Don't add summary/description/changelog/images or anything that should be provided in upstream repo.
* [x] Releases are tagged and auto update is enabled unless there is a special reason.
* [x] There is an issue tracker and contact info of the author so that we can report bugs and contact the author.
* [x] An AuthorName must be added. It doesn't need to be the real name.
* [x] External repos are added as git submodules instead of srclibs.
* [ ] Enable [Reproducible Builds](https://f-droid.org/docs/Reproducible_Builds). Not enabled, see "Reproducible builds" above.
* [ ] Setup abi split if the APK is large and the splitted ones can be much smaller. Not set up: the APK is about 18 MB and the native decoder is about 0.6 MB per ABI.
* [x] Only the latest versions should be kept in the metadata before it's merged.
* [x] Don't add any disabled versions in the metadata.
* [x] The `commit` field should be the full hash.

### Pipeline

* [ ] All pipelines should pass.
* [ ] All warnings and errors in the Reports tab should be fixed or explained.

/label ~"New App"
