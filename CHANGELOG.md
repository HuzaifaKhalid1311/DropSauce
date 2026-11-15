# Changelog

## Unreleased

### Improved
- In landscape and on wide screens the details page shows the chapters list next to the details

### Fixed
- Sort button and filter chips missing when browsing a source in landscape

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
