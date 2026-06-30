```markdown
# DropSauce Development Patterns

> Auto-generated skill from repository analysis

## Overview
This skill provides a comprehensive guide to the development patterns and workflows used in the DropSauce Kotlin codebase. It covers coding conventions, file organization, and step-by-step instructions for implementing new features that span both UI (ViewModel) and domain/model logic. This guide is ideal for contributors seeking to maintain consistency and efficiency while working on DropSauce.

## Coding Conventions

### File Naming
- Use **PascalCase** for file names.
  - Example: `FeedViewModel.kt`, `TrackingLogItem.kt`

### Import Style
- Use **relative imports** within the project.
  - Example:
    ```kotlin
    import org.koitharu.kotatsu.tracker.domain.model.TrackingLogItem
    ```

### Export Style
- Use **named exports** for classes and functions.
  - Example:
    ```kotlin
    class FeedViewModel { ... }
    ```

### Commit Messages
- Use the `feat` prefix for new features.
- Keep commit messages concise (average ~56 characters).
  - Example: `feat: add tracking log item to feed viewmodel`

## Workflows

### Feature Development with ViewModel and Model Update
**Trigger:** When adding or enhancing a feature that requires changes in both the UI ViewModel and underlying domain/model classes. Commonly used for feed or tracking functionality.

**Command:** `/new-feature-ui-domain`

**Step-by-step Instructions:**

1. **Update or create the ViewModel class for the UI feature**
   - Example: `FeedViewModel.kt`
   - ```kotlin
     class FeedViewModel : ViewModel() {
         // Implement feature logic here
     }
     ```

2. **Modify or add domain/model classes**
   - Example: `TrackingLogItem.kt`, `MangaListMapper.kt`
   - ```kotlin
     data class TrackingLogItem(
         val id: Long,
         val action: String,
         val timestamp: Long
     )
     ```

3. **Update or create data access objects (DAOs) or repositories as needed**
   - Example: `TracksDao.kt`, `TrackingRepository.kt`, `ChaptersDao.kt`
   - ```kotlin
     interface TracksDao {
         fun insert(item: TrackingLogItem)
         // Other DAO methods
     }
     ```

4. **Update or add UI adapters or models**
   - Example: `FeedItemAD.kt`, `FeedItem.kt`
   - ```kotlin
     data class FeedItem(
         val title: String,
         val description: String
     )
     ```

5. **Update supporting utility or extension files if necessary**
   - Example: `Date.kt`
   - ```kotlin
     fun Long.toFormattedDate(): String { /* ... */ }
     ```

6. **Update resource files if needed**
   - Example: `plurals.xml`
   - ```xml
     <plurals name="number_of_items">
         <item quantity="one">%d item</item>
         <item quantity="other">%d items</item>
     </plurals>
     ```

**Files Commonly Involved:**
- `app/src/main/kotlin/org/koitharu/kotatsu/tracker/ui/feed/FeedViewModel.kt`
- `app/src/main/kotlin/org/koitharu/kotatsu/tracker/domain/model/TrackingLogItem.kt`
- `app/src/main/kotlin/org/koitharu/kotatsu/list/domain/MangaListMapper.kt`

**Frequency:** ~2x/month

---

## Testing Patterns

- **Framework:** Unknown (not detected)
- **Test file pattern:** Files matching `*.test.*`
  - Example: `FeedViewModel.test.kt`
- **Best Practice:** Place tests alongside or in a parallel directory to the code under test, using the `.test.` naming convention.

---

## Commands

| Command                | Purpose                                                                 |
|------------------------|-------------------------------------------------------------------------|
| /new-feature-ui-domain | Scaffold a new feature involving both UI ViewModel and domain/model logic|
```
