# Changelog

## Unreleased

### New
- Option to hide the line between chapters when reading novels in scroll mode
- Up to 6 sections can be added to the navigation bar
- App icon picker in settings > appearance: default, mono, or themed to the app's color scheme, following light, dark and black mode
- Explore can show only manga or only novels (hold the tab)
- Search several genres at once from the genre chips in the search bar
- Add your own genre chips to the search bar with the + chip, hold one to remove it
- Hide a recent extension from search suggestions with the eye button, it comes back once you use it again
- Sleep timer for text to speech
- Tap zone presets (left to right, right to left, webtoon) in the tap zone settings
- Retry all failed downloads from the downloads menu
- Export the log in settings > about without turning logging off

### Improved
- In landscape and on wide screens the details page shows the chapters list next to the details
- PDF imports are much faster and take far less space
- Reader chapter arrows dim when there is no previous or next chapter
- Continue reading button now shows on every tab in landscape and on tablets, with the same rounder shape everywhere
- "Migrate broken sources" is now "Migrate extensions": works on any extension, lets you pick and order the extensions to search in, and can show every match for review first
- Global search options are a 3-dot menu, with the search type in a submenu
- Genre search now really filters by genre in each extension, the same way Mihon does. Extensions without that genre as a filter search its name as text and are listed after the rest
- Search bar genre chips are 10 common genres, sorted A to Z
- Novel search shows every match in a chapter, with a result count
- The restore dialog shows how many items each backup section has
- Tapping the text to speech notification opens the reader
- The favorite button on the details page is one button again. Quick add is now off by default: turn it on in manage categories > default category, then tap adds to that category and hold picks categories. A one-time note in the "Add to" dialog points there

### Fixed
- Sort button and filter chips missing when browsing a source in landscape
- Large PDFs failing to import
- Manga from Kotatsu backups showing no chapters or errors on about 90 sources (MangaDex, Webtoons, MANGA Plus, MangaLib, MangaTaro, Asura, Flame, Vortex and more), and duplicating when found through search
- Kotatsu backups no longer move manga from a few sites onto an unrelated extension
- Gap before the back button in the search bar in landscape
- Picked genres flashing back for a moment when reopening the search bar
- Search suggestions setting showed every type as off, search suggestions are turned back on for everyone
- Deleted favorites and history showing up in filters, sorting and genre lists
- Downloads of chapters with no pages counting as downloaded, and queued downloads breaking when download conditions changed
- Download "next unread chapters" ignoring the selected branch
- Tap zones in split screen, reversed double page jumping the wrong way, and webtoon zoom resetting on resize
- Other small bug fixes

## 1.1.4 (2026-11-15)

This is a small update you can skip: it is mainly there to get the app onto F-Droid, with some small UI changes.

### Improved
- Redesigned the display options menu and the reader options menu for manga and novels
- Two page layout options moved to settings > reader, and now on by default
- Backups now include pinned sources, extension settings, saved filters, novel plugin settings and every manga's reader mode

### Fixed
- Bookmarking a page again after removing its bookmark failing with an error
- Random crash when the app was reopened in the background
- Backups missing source settings and chapters, and restores stopping early on a damaged entry
- Backups no longer carry device-only settings like storage folders or the Discord login

## 1.1.3 (2026-11-13)

### New
- JPEG XL image support (fixes sources that use it, like Kagane)
- Quick add to default category button on the details page (hold it to change or disable)
- Option to split wide pages manually with a button instead of automatically (settings > reader > fit wide pages)
- Extensions per row option (settings > extensions)

### Improved
- Refreshed settings design with visual previews for theme, list layout, and reader mode
- Smooth fade transition when switching between light and dark theme
- Faster, lower-memory loading for AVIF and JPEG XL pages
- Pull to refresh in extensions now fully reloads the list
- Sort order and chapter sort menus are now submenus
- Smoother description expand and collapse animation on the details page
- Outlined popups

### Fixed
- Excluded genres showing up in suggestions
- OLED dark mode backgrounds
- EPUB dictionary popup not expanding or scrolling properly
- Other small bug fixes
