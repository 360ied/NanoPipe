# Caption language filter — implementation plan

Add a transient, text-based language filter to the player's caption
chooser without changing which caption streams are extracted, loaded, or
selected by ExoPlayer.

- Status: **proposed; not implemented**.
- Audited: 2026-11-04, `main` at `f6cc6e6` (revalidate references before
  implementation).
- Scope: the caption chooser opened from the player controls.
- Interpretation: "filter by language" means typing part of a visible
  language name to narrow the current chooser. It does **not** mean a
  persistent language allowlist, automatic caption selection, or filtering
  the download dialog's subtitle spinner.
- All paths below are relative to the repository root.

## 1. Does the feature already exist?

**No caption-list language filter exists in the audited tree.** A
repository-wide search for caption/subtitle filtering and language-specific
selection found these related but different behaviors:

| Existing behavior | Evidence | Why it is not this feature |
| --- | --- | --- |
| The player lists every available caption | `PlayerTracksController.onTextTracksChanged()` collects every text track's language label and passes the complete list to `PlayerMenuController.buildCaptionMenu()` (`PlayerTracksController.kt:47-86`, `PlayerMenuController.kt:256-312`). | There is no query input, filtered projection, or language allowlist. The only non-language row is `No Captions`. |
| A chosen caption is remembered | Selecting a row stores its label in `caption_user_set_key`; later track updates restore that preferred label (`PlayerMenuController.kt:283-351`, `settings_keys.xml:210-211`). | This reselects a caption. It does not hide nonmatching rows. |
| Auto-generated tracks are labelled | `PlayerHelper.captionLanguageOf()` appends the localized `Auto-generated` suffix (`PlayerHelper.java:173-188`). | This is display metadata, not filtering. |
| YouTube auto-translation has settings | `advanced_settings.xml:229-243` exposes `show_auto_translated_subtitles` and `auto_translated_subtitles_language`; `ServiceHelper.java:227-235` passes them to the extractor. | These choose at most one additional translation target and whether that additional track may be added. They do not filter the chooser by an arbitrary typed language. |
| The extractor creates the subtitle stream list | `YoutubeStreamExtractor.getSubtitles()` always iterates the source `captionTracks`; it conditionally appends one auto-translated stream (`YoutubeStreamExtractor.java:1876-1953`). | This controls stream discovery, not the player list's visible rows. Disabling auto-translation does not remove the source's auto-generated caption tracks. |
| Downloads have a separate subtitle spinner | `DownloadDialog.setupSubtitleSpinner()` and `StreamItemAdapter` render `SubtitlesStream` objects (`DownloadDialog.java:500-507`, `StreamItemAdapter.java:147-176`). | This is a different UI and selection flow; it has no language filter either. |

No caption/subtitle filter implementation was found in the available local
Git history either. Client and extractor history before their inline commit
(`8547680`) is squashed locally, so this audit establishes current behavior,
not an absolute claim about every historical upstream revision.

### Conclusion

This is a new player-UI feature. Existing caption preferences and
auto-translation settings should remain orthogonal to it. No extractor change
is needed for a filter over the language names already shown to the user.

## 2. Current player flow and constraints

1. `VideoPlaybackResolver` creates one ExoPlayer subtitle source per usable
   `SubtitlesStream` (`VideoPlaybackResolver.java:204-244`). It sets
   `Format.language` to `PlayerHelper.captionLanguageOf(...)`: the native
   display language name plus an optional auto-generated suffix.
2. `PlayerTracksController.onTextTracksChanged()` collects those labels in
   track order, finds the selected track, rebuilds the caption menu, updates
   the caption button, and hides the button when no labels are available.
3. `PlayerMenuController.buildCaptionMenu()` currently does two jobs:
   - constructs the `No Captions` row and one row per language; and
   - applies the saved `caption_user_set_key` preference as a side effect.
4. A language click sets preferred text languages to the full label and its
   stem via `captionLanguageStemOf()`, sets `ROLE_FLAG_CAPTION`, enables the
   text renderer, and persists the full label. `No Captions` disables the
   renderer and removes the saved preference.

Important constraints for the new UI:

- **Filter only the rows, not ExoPlayer's tracks.** Removing tracks or
  changing resolver input would alter fallback selection and could make the
  saved caption behave differently merely because a search query is active.
- **Retain the full unfiltered labels.** The visibility check and saved
  preference restoration must not operate on a filtered subset.
- **Keep selection values unchanged.** A filtered row must pass the exact
  original full label to the existing selector logic. Menu positions in the
  filtered list are not track indices.
- **The raw BCP-47 tag is not available in this UI path.** The resolver puts
  the display label, not `SubtitlesStream.getLanguageTag()`, into
  `Format.language`. The first version therefore matches visible names only;
  do not add a parallel label-to-code map or rewrite ExoPlayer language
  semantics just to support queries such as `en-US`.
- **Track-list updates can race an open chooser.** A dialog must not retain a
  previous video's rows or activate a stale language after a queue change.

## 3. Proposed user experience

Replace the caption `PopupMenu` with a small searchable caption dialog:

- Title: `Captions` (reuse `caption_setting_title`).
- A focused text field with a new `Filter by language` hint and an accessible
  label. Show the keyboard after the dialog window is visible.
- A scrollable list of caption rows. With a blank query, this is the complete
  current list in its existing order, including auto-generated suffixes.
- Filtering happens immediately as text changes: trim the query, then use a
  case-insensitive Unicode `contains` match with `Locale.ROOT`. Preserve
  source order and duplicate labels; do not sort or deduplicate.
- Show the existing `search_no_results` string when no language matches.
- Keep `No Captions` visible outside the filtered language list so it remains
  available even when the query has no matches.
- Tapping a language selects it and dismisses the dialog. Back, cancel, and
  outside-touch dismissal change neither captions nor the saved preference.
- Clearing the field restores all rows. The query is transient: it is empty
  on each open and is not stored in preferences or restored for another
  video.

A dialog is preferable to adding more rows to `PopupMenu`: Android's popup
has no suitable persistent text-input area, and a separate filter-entry popup
would add an extra step and obscure which query produced the visible list.

## 4. Implementation steps

### 4.1 Add a pure filtering helper

Add `player/CaptionLanguageFilter.kt` with one small function that accepts
the full `List<String>` and a query and returns a new filtered list.

- Blank or whitespace-only input returns all entries.
- Normalize only for comparison (`trim()` plus `lowercase(Locale.ROOT)`);
  return the original strings, never normalized labels.
- Use substring matching so `eng` matches `English` and `auto` can narrow to
  auto-generated rows.
- Preserve order, duplicates, and empty-source behavior.
- Keep this helper free of Android and ExoPlayer dependencies for JVM tests.

Matching raw language tags, accent folding, fuzzy matching, and multi-token
queries are intentionally deferred; none is required for the visible-name
filter and each would enlarge the data-model or matching contract.

### 4.2 Introduce a dedicated caption dialog

Add a `CaptionSelectionDialog` view/controller and a
`res/layout/dialog_caption_selection.xml` containing the filter field, a
fixed `No Captions` action, a scrollable language list, and an empty-state
view. Reuse simple list-row styling and the current player dialog theme; no
new UI library is needed.

The dialog receives:

- an immutable snapshot of the full language-label list;
- an `onLanguageSelected(String)` callback; and
- an `onCaptionsDisabled()` callback.

It owns only transient UI state (the current query and visible projection)
and delegates every filtering decision to `CaptionLanguageFilter`. Its
callbacks return the original label, not a filtered-list position. Manage IME
visibility when showing and dismissing so the keyboard cannot remain over the
player after the dialog closes.

### 4.3 Separate track updates from caption commands

Refactor `PlayerMenuController` without changing selection semantics:

- Replace `buildCaptionMenu(availableLanguages)` with an update method that
  stores the complete list. Keep `availableLanguages` as the canonical source
  for the next dialog open.
- Extract the body of the current language click listener into
  `selectCaptionLanguage(captionLanguage)` and the `No Captions` listener into
  `disableCaptions()`. The dialog calls these methods, preserving track role
  flags, language-stem fallback, renderer handling, and preference writes.
- Move saved-preference restoration out of menu/dialog construction into a
  distinctly named method called by track updates. Filtering or reopening the
  dialog must never reapply preferences or cause an
  `onTextTracksChanged` feedback loop.
- `onCaptionClicked()` opens the dialog only when the canonical list is
  nonempty and marks the selector visible just as the old popup did.
- Route dialog dismissal through the same controls/system-UI cleanup as
  `PopupMenu.OnDismissListener`, while avoiding a double cleanup when both a
  popup and dialog callback fire.
- Extend `closeAllPopupMenus()` to dismiss and clear the caption dialog. Clear
  and dismiss it on new metadata and whenever text tracks become unsupported
  or empty. If the track list changes while open, dismiss it rather than let
  a stale snapshot select into the new list.

No `PlayerTracksController` filtering should occur beyond passing the full
list to the controller and using that same full list for button visibility.
Early return paths must not leave a previous video's dialog or labels alive.

### 4.4 Resources and integration cleanup

- Add only the filter hint and any necessary accessibility strings to
  `res/values/strings.xml`; reuse `Captions`, `No Captions`, and `No results`.
- Remove the now-unused `captionPopupMenu` field, its initialization, and its
  popup dismissal call only after the dialog owns all old entry points.
- Keep `POPUP_MENU_ID_CAPTION` only if still needed after the refactor;
  otherwise remove it with the menu builder.
- Leave `caption_user_set_key`, auto-translation settings, resolver source
  creation, `PlayerHelper.captionLanguageOf()`, and language-stem selection
  unchanged.

## 5. Files expected to change

| Path | Change |
| --- | --- |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/CaptionLanguageFilter.kt` | New pure matching/projection helper. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/CaptionSelectionDialog.kt` | New searchable dialog and transient UI state. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerMenuController.kt` | Own full caption list/dialog; extract selection commands; preserve dismissal and preference behavior. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerTracksController.kt` | Supply full labels on every track-state path and keep visibility based on the full list. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerMetadataController.kt` | Reset/dismiss chooser state on a new item if not covered by an existing common reset path. |
| `PipePipeClient/app/src/main/res/layout/dialog_caption_selection.xml` | New filter/list/empty-state layout. |
| `PipePipeClient/app/src/main/res/values/strings.xml` | Filter hint and accessibility text. |
| `PipePipeClient/app/src/test/java/org/schabi/newpipe/player/CaptionLanguageFilterTest.kt` | New JVM unit tests. |

`PlayerMetadataController` should be touched only if track callbacks and
`closeAllPopupMenus()` do not provide a reliable new-item reset; avoid two
competing reset owners.

## 6. Edge cases and acceptance criteria

- **Empty query:** every existing row appears once in original order; `No
  Captions` is also present.
- **Case and whitespace:** surrounding whitespace is ignored and matching is
  case-insensitive independent of the device locale, including a Turkish
  locale test.
- **Auto-generated captions:** both manual and auto-generated variants remain
  visible for a language query; their labels are not deduplicated or changed.
- **No matches:** a clear empty state appears, the language list is empty,
  and `No Captions` remains usable.
- **Filtered selection:** selecting a row enables the text renderer, stores
  the exact same preference value as before, and chooses the same track as
  selecting that label in the old popup.
- **No Captions:** disables the text renderer and removes
  `caption_user_set_key`, exactly as before.
- **No text tracks:** the caption button is hidden and no stale dialog can be
  opened or remain visible.
- **Duplicate labels:** filtering neither merges them nor uses visible-list
  indices as ExoPlayer track indices. Any pre-existing inability to
  distinguish identical labels is not expanded into a new selection bug.
- **Lifecycle:** queue changes, player destruction, fullscreen/popup mode,
  rotation, backgrounding, and rapid open/close do not leak windows, leave the
  keyboard visible, retain the previous video's rows, or wedge player controls
  behind `isSomePopupMenuVisible`.
- **Preferences:** the search query never changes automatic caption selection,
  auto-translation settings, or caption choices on later videos.

## 7. Verification plan

### JVM tests

Add table-driven tests for blank/whitespace queries, case-insensitive partial
matches, `Locale.ROOT` behavior, no matches, empty input lists, source-order
preservation, duplicate preservation, auto-generated suffix matching, and
returning original rather than lowercased labels. Also verify the input list
is not mutated.

Run the full repository gate because this change adds unit tests:

```sh
./scripts/check.sh --full
```

### Manual player matrix

Use one video with multiple caption languages and, where available, both
manual and auto-generated tracks:

1. Open the chooser and verify the unfiltered list matches the old popup.
2. Filter by a partial language name, clear the query, enter a no-match query,
  and select from a filtered list.
3. Confirm `No Captions`, saved-caption restoration on a later video, and the
  caption button's selected/none text.
4. Toggle auto-translation in advanced settings and confirm it affects stream
  discovery exactly as before, independently of the transient filter.
5. Repeat dialog open/filter/select/dismiss in portrait, landscape,
  fullscreen, and popup playback. Switch queue items and destroy the player
  while the dialog is open; check controls, system UI, keyboard, and window
  cleanup.
6. Confirm the download subtitle spinner is unchanged.

## 8. Explicit non-goals

- Filtering or grouping the download dialog's subtitles.
- A persistent allowed-language list or automatic preferred-language choice.
- Fetching every YouTube translation or changing auto-translated subtitle
  extraction.
- Matching BCP-47 codes, localized aliases, accents, or fuzzy spellings.
- Deduplicating manual/auto-generated tracks or redesigning track selection.
- Changing caption styling, caption defaults, or `caption_user_set_key`
  persistence/migration.
