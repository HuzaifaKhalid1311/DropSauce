# Changelog

## Unreleased

### Improved
- Redesigned the updates feed, with last updated time, source filters and clearing read entries
- Pop-up messages always show above the nav bar now, and you can swipe them down to dismiss
- History's date sections are more compact, and you can fold them all at once too

### Fixed
- Getting new chapter notifications way more often than the check frequency you picked
- Copy details on the crash popup copying nothing or crashing the app
- Other small bug fixes

## 1.2.0 (2026-11-27)

### New
- PDFs got a big upgrade: links work, you can search, select and highlight text, chapters come from the table of contents, and zoom stays sharp
- Track your reading on Comick
- Hold a suggestion in Explore to never see it again
- New Erza theme, a deep red
- Static app icon option, for launchers that glitch with the themed ones

### Improved
- Redesigned Explore, with a manga/novel switcher and a chips, grid or list layout
- Redesigned history, with sections you can fold away
- New scrollbar that's easier to grab
- Nicer option pickers in settings and the reader
- Searching a book's text shows the results right in the search popup
- The Expressive theme is now called Dynamic

### Fixed
- Newer Keiyoushi extensions like MangaFire failing on a manga's page
- Wrong tracker ratings and statuses after restoring a Mihon backup
- Opening a chapter sometimes opening the one next to it
- Black text and icons in the Dynamic theme on some phones
- The crash report popping up on every start
- Up to four suggestion notifications at once
- Other small bug fixes

## 1.1.5 (2026-11-20)

### New
- Pick your app icon: default, mono, or one that matches your theme (settings > appearance)
- Search several genres at once, and add your own genre chips
- Up to 6 sections in the bottom bar
- Show only manga or only novels in Explore (just hold the tab)
- Sleep timer for text to speech
- Retry all failed downloads in one go

### Improved
- PDFs open instantly, even huge ones, and don't take up extra space
- Genre search gives much better results, the same way Mihon does it
- Migrate any extension, not just broken ones, and choose where to look
- Alternatives finds a lot more matches
- Better on tablets and in landscape: chapters sit next to the details, and the continue button shows everywhere
- The favorite button is back to one simple tap

### Fixed
- Kotatsu backups restore properly from around 90 sources like MangaDex, Webtoons and Asura
- Deleting one chapter of an imported folder no longer deletes the whole thing
- Folders with both comics and EPUBs now import both
- Search suggestions are back
- Plenty of download, reader and other small fixes

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
