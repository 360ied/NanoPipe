# Caption language filter — implementation plan

Add a Settings-based caption-language allowlist for extracted, side-loaded
subtitles. Users choose languages from a standard multi-choice checklist; the
existing player caption popup then shows only allowed side-loaded tracks.
Live and manifest-embedded captions are explicitly outside this feature's
scope. No search field, freeform text, or keyboard input is added to ExoPlayer.

- Status: **proposed; not implemented**.
- Implemented in branch `feat/caption-language-filter` with one deliberate
  deviation: a searchable custom dialog replaces the specified
  `MultiSelectListPreference` (see §2). The stock multi-choice dialog rendered
  zero rows for runtime-populated entries on-device despite a healthy
  239-item adapter (verified via adapter count); root cause unidentified.
- Audited: 2026-11-04, `main` at `2ac2f21` (revalidate references before
  implementation).
- Scope: **Settings → Video and audio** and `StreamInfo` side-loaded subtitle
  sources created by `VideoPlaybackResolver`.
- Default: no languages selected means **show all**, preserving current
  behavior for every existing user.
- All paths below are relative to the repository root.

## 1. Existing behavior and feature audit

No caption-language filter exists in the audited tree.

- `PlayerTracksController.onTextTracksChanged()` collects every ExoPlayer text
  track's language label and passes the complete list to
  `PlayerMenuController.buildCaptionMenu()`
  (`PlayerTracksController.kt:47-86`, `PlayerMenuController.kt:256-344`).
- `caption_user_set_key` remembers the selected display label and reapplies it
  on later track updates. It selects a caption; it does not filter the list.
- `show_auto_translated_subtitles_key` and
  `auto_translated_subtitles_language_key` control whether YouTube extraction may
  append one translated track. They are not a general caption allowlist.
- `YoutubeStreamExtractor.getSubtitles()` always processes the source
  `captionTracks` and conditionally appends that translation
  (`YoutubeStreamExtractor.java:1878-1951`, subject to the `hasDuplicateEntries`
  gate). Source auto-generated tracks are
  still returned when the additional translation is disabled.
- `DownloadDialog` has a separate subtitle spinner backed directly by
  `SubtitlesStream` objects. It has no language filter and is not part of this
  feature.

The available local history contains no caption-language filter either.
Client and extractor history before their inline commit (`8547680`) is
squashed locally, so this establishes current behavior rather than ruling out
every historical upstream implementation.

## 2. User-facing design

Add **Caption languages** to **Settings → Video and audio**, immediately next
to the existing Captions preference that opens Android's caption-style
settings.

Tapping it opens a searchable checklist dialog (`CaptionLanguageSelectionDialog`):

- A guidance header explains the empty-means-all rule and the
  media-source-resolution timing.
- A search field filters the list by localized name or code as the user types;
  keyboard interaction is confined to Settings (the ExoPlayer no-keyboard rule
  is unchanged).
- Each row is a language name with a checkbox. Users can select one language
  or several languages.
- Selected languages float to the top of the list whenever the dialog is
  opened or the query changes, so a saved selection is easy to review and
  deselect. Toggling a checkbox never reshuffles rows mid-interaction.
- **OK** saves the normalized selection; **Cancel** discards all changes made
  since opening.
- Selecting no languages means **All languages**, not **No captions**.
- The preference summary shows **All languages** when empty. Otherwise it
  shows the selected names, collapsing to a count when too many are selected
  for a concise summary.

This uses configuration for a durable preference rather than making users
repeat a search whenever they open the caption popup. For supported
side-loaded captions, the player interaction itself remains unchanged: tap
the caption button, inspect the shorter popup, and select a track or
**No Captions**.

### Why a multi-choice setting?

A single-choice preference would enforce one language even when a user
regularly watches content in two or more. A checklist handles both cases with
the same control and does not require a second enable/disable switch.

### Why empty means all?

Defaulting to all is backward compatible and avoids hiding captions
immediately after an app upgrade. It also avoids storing every known language
as the default, which would make newly introduced language entries
unexpectedly excluded. The dialog text makes the empty-set behavior explicit.

### Why apply when media sources are resolved?

Subtitle sources are assembled while `VideoPlaybackResolver` builds a video's
`MediaSource`. Reloading an active video merely because Settings changed
would interrupt playback and require position/recovery handling. Reading the
allowlist at resolution time gives deterministic behavior without surprise
reloads.

The exact boundary is **the next media-source resolution**, not simply
starting another video. `MediaSourceManager` preloads neighboring queue items
and retains their loaded sources. The current item and already-preloaded
items therefore keep their old caption sources until those sources are
invalidated or reloaded. Starting an already-preloaded queue item does not,
by itself, apply a changed allowlist.

## 3. Language model and matching

The checklist displays localized language names but stores canonical BCP-47
primary language codes, including both two- and three-letter codes, not
display labels.

- Populate entries from deduplicated `Locale.getAvailableLocales()` primary
  language subtags rather than `Locale.getAvailableISOLanguages()`. Retain
  valid two- and three-letter codes, supplementing the platform-derived set
  if needed so the supported vocabulary explicitly includes `yue`
  (Cantonese), `ceb` (Cebuano), and `haw` (Hawaiian). Do not duplicate the
  small auto-translation target list, which is not a complete vocabulary.
  Skip blank or empty primary subtags when building the vocabulary.
- Display each language with `Locale.getDisplayLanguage()` in the Settings UI
  locale and sort names with the current locale's `Collator`.
- Fall back to the uppercase code if a platform locale has no display name.
- Canonicalize and deduplicate entry values before storing normalized
  lowercase primary codes such as `en`, `fr`, `pt`, `zh`, and `yue` in a
  string-set preference.
- Match against `SubtitlesStream.getLanguageTag()`, which is still available
  before the resolver converts streams into ExoPlayer sources. Compare
  canonical primary language subtags rather than native display names.
- Canonicalize both preference codes and track primaries through one explicit
  alias-normalization function. Map legacy `iw`/`in`/`ji` and modern
  `he`/`id`/`yi` to the same respective canonical values (`he`, `id`, `yi`)
  so equivalent Hebrew, Indonesian, and Yiddish tags always match.
- Selecting `en` therefore includes `en`, `en-US`, and `en-GB`; selecting
  `pt` includes both `pt-PT` and `pt-BR`. Regional variants are not separate
  settings in this version.
- Matching is case-insensitive and tolerates normal BCP-47 tags. Normalize
  underscore separators defensively and use `Locale.ROOT` for lowercase
  conversion, but never parse display labels.
- Manual, auto-generated, and auto-translated tracks with the same primary
  language are treated identically. This setting filters language, not
  caption provenance.
- Null, blank, or unparseable track tags do not match a nonempty allowlist.
  They still appear when the allowlist is empty. `SubtitlesStream` currently
  requires a nonnull code, so this is defensive behavior rather than an
  expected YouTube case.

Using raw tags avoids fragile comparisons between Settings names and
ExoPlayer labels such as `English (Auto-generated)`. It also fixes the main
limitation of matching visible names: regional tags have well-defined
language semantics.

## 4. Implementation steps

### 4.1 Add the Settings preference

Add a plain `Preference` row for **Caption languages** to
`PipePipeClient/app/src/main/res/xml/video_audio_settings.xml` near the
existing Captions preference. Tapping it opens `CaptionLanguageSelectionDialog`
(a `DialogFragment` with its own RecyclerView checklist, defined in
`dialog_caption_language_selection.xml` with rows from
`item_caption_language_selection.xml`), so no stock multi-choice dialog is
used. Runtime entries are built by the dialog itself; no XML language arrays
are required.

Add resources for:

- `caption_language_filter_key` in `res/values/settings_keys.xml`;
- the **Caption languages** title;
- the **All languages** and selected-language summaries; and
- dialog guidance explaining empty selection, media-source resolution timing,
  and retention of current or already-preloaded sources.

No XML language arrays are required because entries and values are generated
at runtime. An absent, null, or empty stored set all mean no filtering. No
migration or preferences-version bump is needed.

### 4.2 Populate, summarize, and persist the checklist

In `VideoAudioSettingsFragment`:

1. Build the sorted language-name/canonical-code mapping once when preferences
   are created, for summary rendering only. The dialog builds its own copy.
   Verify that the two- and three-letter vocabulary includes `yue`, `ceb`,
   and `haw`.
2. Render the summary from the stored set and the same name/code mapping:
   **All languages** when empty, up to three names otherwise, and a localized
   `N languages selected` summary beyond that limit.
3. Normalize the stored set on setup, on resume, and on every change to the
   key, so externally written values converge to the effective filter.
4. Open `CaptionLanguageSelectionDialog` on row tap; refresh the summary when
   the stored set changes.

In `CaptionLanguageSelectionDialog`:

1. Rebuild the vocabulary on open and seed a working copy from the stored set.
2. Order the visible list selected-first (stable within each group) on open
   and on every query change; never reorder on toggle.
3. Filter rows with the pure `filterEntriesByQuery` helper as the user types.
4. Persist the normalized working copy only on save; cancel discards it.
   The fragment's change listener then refreshes the summary.

### 4.3 Add a small language-filter helper

Add `util/CaptionLanguageFilter.java` with pure, testable matching/filtering
methods. It should:

- accept the selected primary-code set and a subtitle list or language tag;
- return the original `SubtitlesStream` objects in their original order in a
  **new, independent list**, including when the selection is absent/empty;
- bypass filtering semantically for an absent/empty selection without
  returning the source backing list;
- canonicalize aliases and compare primary language subtags;
- avoid mutating either the preference set or source subtitle list; and
- handle null/empty lists and defensive malformed tags without throwing.

Keep SharedPreferences access outside the pure matching method. In
particular, do not modify the mutable set instance returned by
`SharedPreferences.getStringSet()`.

### 4.4 Filter only player subtitle sources

In `VideoPlaybackResolver.resolve(...)`, read the selected set from default
SharedPreferences and apply `CaptionLanguageFilter` to `info.getSubtitles()`
before the existing loop that creates `MediaItem.SubtitleConfiguration` and
`MediaSource` objects (`VideoPlaybackResolver.java:204-244`).

Before calling `removeTorrentStreams()`, wrap the filtered result in a new
`ArrayList` at the resolver seam as well. This explicit defensive copy is
required even if the helper already returns a new list: `StreamInfo`
`getSubtitles()` exposes its backing list and `removeTorrentStreams()` removes
elements through that list's iterator. Helper-only immutability tests cannot
prove that this downstream mutation is safe.

Consequences, limited to extracted side-loaded subtitles:

- The separately created subtitle `MediaSource` objects contain only allowed
  tracks, so ExoPlayer's existing callbacks naturally build a filtered popup
  for those sources.
- `PlayerTracksController.availableLanguages`, caption-button visibility,
  and `PlayerMenuController` require no filtering logic of their own for this
  scoped implementation.
- When a video has only side-loaded captions and none match, the caption
  button is hidden as it is for a video with no captions.
- Live playback returns before this filtering seam, and DASH/HLS sources can
  expose manifest-embedded text tracks independently. Those tracks remain
  visible and selectable, can keep the caption button visible, and are not
  covered by any **only allowed tracks** guarantee.
- The resolver still uses `PlayerHelper.captionLanguageOf()` for display and
  the existing role flags and renderer selection remain unchanged.
- The independent resolver list prevents mutation of `StreamInfo`'s backing
  subtitle list. Downloads and other consumers continue to see the complete
  extracted subtitle list.

Do not add this allowlist to `YoutubeStreamExtractor`, `ServiceHelper`, or
`StreamItemAdapter`. Filtering extraction would unintentionally remove
subtitle choices from downloads and would conflate translation configuration
with player presentation.

### 4.5 Preserve saved-caption behavior

Leave `caption_user_set_key` unchanged. If its language is excluded when a
side-loaded source is next resolved, that track is not added to the merged
media source. The existing reapply logic still sets the saved language as the
preferred track with the renderer enabled, so no track is pre-selected and
the user picks from the filtered popup. Do not delete the saved label, so removing the filter can
restore the user's earlier choice without migration. Manifest-embedded tracks
remain governed by their existing behavior.

No new automatic selection is introduced. This feature decides which tracks
are available, not which allowed track the user prefers.

## 5. Expected file changes

| Path | Change |
| --- | --- |
| `PipePipeClient/app/src/main/res/xml/video_audio_settings.xml` | Add the Caption languages preference row (plain `Preference`; the dialog is custom). |
| `PipePipeClient/app/src/main/res/values/settings_keys.xml` | Add its preference key. |
| `PipePipeClient/app/src/main/res/values/strings.xml` | Add title, summaries, dialog guidance, and plural/count text as needed. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/settings/VideoAudioSettingsFragment.java` | Build the vocabulary mapping for summaries, normalize the stored set, and open the dialog on tap. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/settings/CaptionLanguageSelectionDialog.java` | New searchable checklist dialog with working-copy semantics. |
| `PipePipeClient/app/src/main/res/layout/dialog_caption_language_selection.xml` | New dialog layout (guidance, search, list, buttons). |
| `PipePipeClient/app/src/main/res/layout/item_caption_language_selection.xml` | New checkbox row layout. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/util/CaptionLanguageFilter.java` | New primary-language matching, query filtering, selected-first ordering, and subtitle filtering helper. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/resolver/VideoPlaybackResolver.java` | Read the setting and filter subtitle streams before source creation. |
| `PipePipeClient/app/src/test/java/org/schabi/newpipe/util/CaptionLanguageFilterTest.java` | New JVM tests for matching and list behavior. |

`PlayerMenuController`, `PlayerTracksController`, ExoPlayer layouts, and the
extractor should not need changes.

## 6. Acceptance criteria and edge cases

- **Fresh install/upgrade:** with no stored selection, every caption appears
  exactly as before.
- **One language:** selecting English leaves English manual and
  auto-generated side-loaded tracks in the player and removes other
  side-loaded languages.
- **Several languages:** all and only the selected primary languages appear
  among supported side-loaded tracks, in their original relative order.
- **Regional variants:** English includes `en-US`/`en-GB`; Portuguese includes
  `pt-PT`/`pt-BR`; Chinese script variants match `zh`.
- **No matching tracks:** for a video with only side-loaded captions, the
  existing player hides its caption button. Live/manifest-embedded tracks are
  unchanged and may keep it visible.
- **Clear selection:** removing every checkbox restores all side-loaded
  languages when the media source is next resolved.
- **Current and preloaded playback:** changing the setting does not reload or
  interrupt an active video or invalidate already-preloaded queue items. Each
  source retains its old caption set until its next resolution.
- **List independence:** downstream torrent removal cannot mutate the
  original `StreamInfo` subtitle list, including with an empty allowlist.
- **Saved caption:** filtering never rewrites or removes
  `caption_user_set_key`. If the saved language is filtered out, no track is
  pre-selected and the user picks from the filtered popup; clearing the
  filter restores the earlier choice without migration.
- **Auto-translation:** extraction still follows its existing advanced
  settings. An appended translation appears in the player only if its primary
  language is allowed; it remains available to downloads regardless.
- **Downloads:** the subtitle spinner continues to show all extracted tracks.
- **Malformed data:** null/empty subtitle lists and defensive malformed tags
  do not crash resolution.
- **Settings UI:** search filters by name or code; reopening the dialog
  floats the saved selection to the top without reshuffling on toggle;
  cancelling discards all changes; saving persists the normalized set and
  refreshes the summary.

## 7. Verification plan

### JVM tests

Cover:

- absent/empty allowlists returning every input stream;
- one and multiple selected languages;
- exact, regional, mixed-case, and underscore-normalized tags;
- two- and three-letter vocabulary coverage, including `yue`, `ceb`, and
  `haw`;
- legacy/modern aliases `iw`/`he`, `in`/`id`, and `ji`/`yi` in both directions;
- nonmatching and malformed tags;
- original order and object identity in a new, independent result list;
- input-list and selected-set immutability; and
- null/empty source lists.

Add a resolver-seam test proving that downstream `removeTorrentStreams()`
cannot mutate the original `StreamInfo` list; helper-only tests do not cover
this boundary. Add focused tests for any pure summary/name-mapping helper
extracted from the Settings fragment. Keep Android `Locale` and `Collator`
setup deterministic in tests by supplying locales explicitly where the helper
permits it.

Run the full repository gate because this change adds unit tests:

```sh
./scripts/check.sh --full
```

### Manual verification

1. Open **Settings → Video and audio → Caption languages** and confirm the
   guidance header, search field, and scrollable checklist. Type a query and
   confirm the list filters by name or code; clear it to restore the full list.
2. With no selection, play a multi-caption video with side-loaded subtitles
   and compare the popup with current behavior.
3. Select one language, save, reopen the dialog, and confirm the selection
   floats to the top. Toggle a checkbox and confirm rows do not reshuffle.
   Force the media source to resolve again, and confirm only its manual and
   auto-generated side-loaded tracks remain. Add a second language and repeat.
   Do not treat merely selecting a cached queue item as a forced resolution.
4. Test regional and three-letter tags, legacy/modern aliases, and a selection
   with no matching side-loaded track; confirm the caption button hides when
   no other manifest tracks exist.
5. Clear all selections and confirm the complete side-loaded popup returns
   after an actual media-source reload.
6. Change the setting during active playback and after the next queue item
   has preloaded. Confirm neither reloads and that starting the preloaded item
   retains its old captions; verify the new set only after source reload.
7. Verify saved-caption selection after clearing the filter, auto-translation
   interaction, unchanged download subtitles, and unchanged
   live/manifest-embedded captions.
8. Exercise the Settings checklist with touch, TV/d-pad navigation, large
   fonts, and a non-English UI locale.

## 8. Explicit non-goals

- Search fields, freeform text, or any keyboard UI in ExoPlayer.
- Replacing or redesigning the existing player caption popup.
- Filtering live or DASH/HLS manifest-embedded caption tracks; this plan only
  covers extracted, side-loaded `StreamInfo` subtitles.
- Filtering downloads or mutating `StreamInfo`'s extracted subtitle list.
- Changing auto-generated or auto-translated subtitle extraction.
- Separate regional-variant selections, custom language names, or automatic
  preferred-caption selection.
- Immediate reload or invalidation of active or already-preloaded media
  sources when Settings changes.
- Caption styling, saved-caption migration, or changes to **No Captions**.
