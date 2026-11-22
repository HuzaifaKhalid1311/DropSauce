# Changelog

## Unreleased

### New
- Links in PDFs work: web links open in your browser and contents or footnote links jump to the right page
- Long-press text in a PDF to copy it, look it up in the dictionary or highlight it, and your highlights show up in your bookmarks (Android 15, or 12 to 14 with recent system updates)
- PDFs with a table of contents now show their real chapters, and you keep your place
- Search a PDF's text with the search button at the top of the reader, and the matches get marked on the page
- Track your reading on Comick, sign in from the services settings

### Improved
- The chapters popup on a manga's page is a lighter colour and opens right under the cover or progress card
- PDFs stay sharp however far you zoom in
- Searching a book's text shows the results right in the search popup, with your words highlighted
- More sensitive tags like self-harm or nudity get the orange warning colour, and harmless ones like sister or scar don't anymore
- New Static app icon that never changes colour, for launchers that glitch with the themed ones

### Fixed
- Newer Keiyoushi extensions like MangaFire no longer fail on a manga's page with a "must not be called concurrently" error
- The dictionary popup opens at half the screen instead of jumping to full screen for long meanings
- Restoring a Mihon backup brings back the right tracker ratings and statuses
- Opening a chapter no longer occasionally lands on a neighbouring chapter instead of the one you tapped

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
