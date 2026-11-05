# Caption language filter — implementation plan

Add a Settings-based caption-language allowlist. Users choose languages from
a standard multi-choice checklist; the existing player caption popup then
shows only tracks in those languages. No search field, freeform text, or
keyboard input is added to ExoPlayer.

- Status: **proposed; not implemented**.
- Audited: 2026-11-04, `main` at `f3c73f0` (revalidate references before
  implementation).
- Scope: **Settings → Video and audio** and player subtitle-source creation.
- Default: no languages selected means **show all**, preserving current
  behavior for every existing user.
- All paths below are relative to the repository root.

## 1. Existing behavior and feature audit

No caption-language filter exists in the audited tree.

- `PlayerTracksController.onTextTracksChanged()` collects every ExoPlayer text
  track's language label and passes the complete list to
  `PlayerMenuController.buildCaptionMenu()`
  (`PlayerTracksController.kt:47-86`, `PlayerMenuController.kt:256-312`).
- `caption_user_set_key` remembers the selected display label and reapplies it
  on later track updates. It selects a caption; it does not filter the list.
- `show_auto_translated_subtitles` and
  `auto_translated_subtitles_language` control whether YouTube extraction may
  append one translated track. They are not a general caption allowlist.
- `YoutubeStreamExtractor.getSubtitles()` always processes the source
  `captionTracks` and conditionally appends that translation
  (`YoutubeStreamExtractor.java:1876-1953`). Source auto-generated tracks are
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

Tapping it opens the platform `MultiSelectListPreference` dialog:

- Each row is a language name with a checkbox.
- There is no text field and therefore no keyboard interaction.
- Users can select one language or several languages.
- Selecting no languages means **All languages**, not **No captions**.
- The dialog explains: selected languages are shown in the player; select
  none to show all; changes apply to newly loaded videos.
- The preference summary shows **All languages** when empty. Otherwise it
  shows the selected names, collapsing to a count when too many are selected
  for a concise summary.

This uses configuration for a durable preference rather than making users
repeat a search whenever they open the caption popup. The player interaction
itself remains unchanged: tap the caption button, inspect the shorter popup,
and select a track or **No Captions**.

### Why a multi-choice setting?

A single-choice preference would enforce one language even when a user
regularly watches content in two or more. A checklist handles both cases with
the same control and does not require a second enable/disable switch.

### Why empty means all?

Defaulting to all is backward compatible and avoids hiding captions
immediately after an app upgrade. It also avoids storing every known language
as the default, which would make newly introduced language entries
unexpectedly excluded. The dialog text makes the empty-set behavior explicit.

### Why apply on newly loaded videos?

Subtitle sources are assembled while `VideoPlaybackResolver` builds the
current `MediaSource`. Reloading an active video merely because Settings
changed would interrupt playback and require position/recovery handling.
Reading the allowlist at resolution time gives deterministic behavior without
surprise reloads. Returning to a video that is reloaded, starting another
video, or restarting playback picks up the new setting.

## 3. Language model and matching

The checklist displays localized language names but stores stable ISO 639-1
language codes, not display labels.

- Populate entries from `Locale.getAvailableISOLanguages()` rather than
  duplicating the small auto-translation target list. The latter is only a
  set of translation targets, not a complete caption-language vocabulary.
- Display each language with `Locale.getDisplayName()` in the Settings UI
  locale and sort names with the current locale's `Collator`.
- Fall back to the uppercase code if a platform locale has no display name.
- Store normalized lowercase primary codes such as `en`, `fr`, `pt`, and
  `zh` in a string-set preference.
- Match against `SubtitlesStream.getLanguageTag()`, which is still available
  before the resolver converts streams into ExoPlayer sources. Compare the
  primary language subtags rather than native display names.
- Selecting `en` therefore includes `en`, `en-US`, and `en-GB`; selecting
  `pt` includes both `pt-PT` and `pt-BR`. Regional variants are not separate
  settings in this version.
- Matching is case-insensitive and tolerates normal BCP-47 tags. Underscore
  separators may be normalized defensively, but display-label parsing is not
  allowed.
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

Add a `MultiSelectListPreference` to
`PipePipeClient/app/src/main/res/xml/video_audio_settings.xml` near the
existing Captions preference.

Add resources for:

- `caption_language_filter_key` in `res/values/settings_keys.xml`;
- the **Caption languages** title;
- the **All languages** and selected-language summaries; and
- dialog guidance explaining empty selection and newly-loaded-video
  behavior.

No XML language arrays are required because entries and values are generated
at runtime. An absent, null, or empty stored set all mean no filtering. No
migration or preferences-version bump is needed.

### 4.2 Populate and summarize the checklist

In `VideoAudioSettingsFragment`:

1. Build the sorted language-name/code pairs once when preferences are
   created and assign them to the preference's entries and entry values.
2. Render the summary from the stored set and the same name/code mapping.
3. Extend the existing preference-change listener to refresh that summary
   when `caption_language_filter_key` changes.
4. Refresh the summary on resume as well, matching the fragment's existing
   lifecycle pattern.

Use a short list of selected names for a small selection and a localized
`N languages selected` summary beyond that limit. Do not add a custom
preference dialog; the standard multi-choice UI already supplies checkboxes,
scrolling, accessibility, TV navigation, and cancellation.

### 4.3 Add a small language-filter helper

Add `util/CaptionLanguageFilter.java` with pure, testable matching/filtering
methods. It should:

- accept the selected primary-code set and a subtitle list or language tag;
- return the original `SubtitlesStream` objects in their original order;
- bypass filtering for an absent/empty selection;
- normalize and compare primary language subtags;
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

Consequences:

- ExoPlayer receives only allowed subtitle tracks, so its existing track
  callbacks naturally build a filtered caption popup.
- `PlayerTracksController.availableLanguages`, caption-button visibility,
  and `PlayerMenuController` require no filtering logic of their own.
- A selection that matches no tracks produces the same state as a video with
  no captions: the caption button is hidden.
- The resolver still uses `PlayerHelper.captionLanguageOf()` for display and
  the existing role flags and renderer selection remain unchanged.
- Filtering occurs only in `VideoPlaybackResolver`; `StreamInfo` itself is
  not mutated. Downloads and other consumers continue to see the complete
  extracted subtitle list.

Do not add this allowlist to `YoutubeStreamExtractor`, `ServiceHelper`, or
`StreamItemAdapter`. Filtering extraction would unintentionally remove
subtitle choices from downloads and would conflate translation configuration
with player presentation.

### 4.5 Preserve saved-caption behavior

Leave `caption_user_set_key` unchanged. If its language is excluded on a
later video, that track simply is not loaded; ExoPlayer can only select from
the allowed tracks. Do not delete the saved label, so removing the filter can
restore the user's earlier choice without migration.

No new automatic selection is introduced. This feature decides which tracks
are available, not which allowed track the user prefers.

## 5. Expected file changes

| Path | Change |
| --- | --- |
| `PipePipeClient/app/src/main/res/xml/video_audio_settings.xml` | Add the standard multi-choice Caption languages preference. |
| `PipePipeClient/app/src/main/res/values/settings_keys.xml` | Add its preference key. |
| `PipePipeClient/app/src/main/res/values/strings.xml` | Add title, summaries, dialog guidance, and plural/count text as needed. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/settings/VideoAudioSettingsFragment.java` | Generate sorted language entries/values and maintain the summary. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/util/CaptionLanguageFilter.java` | New primary-language matching and subtitle filtering helper. |
| `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/resolver/VideoPlaybackResolver.java` | Read the setting and filter subtitle streams before source creation. |
| `PipePipeClient/app/src/test/java/org/schabi/newpipe/util/CaptionLanguageFilterTest.java` | New JVM tests for matching and list behavior. |

`PlayerMenuController`, `PlayerTracksController`, ExoPlayer layouts, and the
extractor should not need changes.

## 6. Acceptance criteria and edge cases

- **Fresh install/upgrade:** with no stored selection, every caption appears
  exactly as before.
- **One language:** selecting English leaves English manual and
  auto-generated tracks in the player and removes other languages.
- **Several languages:** all and only the selected primary languages appear,
  in their original relative order.
- **Regional variants:** English includes `en-US`/`en-GB`; Portuguese includes
  `pt-PT`/`pt-BR`; Chinese script variants match `zh`.
- **No matching tracks:** the existing player hides its caption button; no
  blank popup or new keyboard-based empty state is introduced.
- **Clear selection:** removing every checkbox restores all languages on the
  next loaded video.
- **Current playback:** changing the setting does not reload or interrupt an
  active video; the next resolution uses the new set.
- **Saved caption:** filtering never rewrites or removes
  `caption_user_set_key`.
- **Auto-translation:** extraction still follows its existing advanced
  settings. An appended translation appears in the player only if its primary
  language is allowed; it remains available to downloads regardless.
- **Downloads:** the subtitle spinner continues to show all extracted tracks.
- **Malformed data:** null/empty subtitle lists and defensive malformed tags
  do not crash resolution.
- **Settings UI:** language names are localized and sorted, checkboxes restore
  their stored values, cancellation makes no changes, and no keyboard appears.

## 7. Verification plan

### JVM tests

Cover:

- absent/empty allowlists returning every input stream;
- one and multiple selected languages;
- exact, regional, mixed-case, and underscore-normalized tags;
- nonmatching and malformed tags;
- original order and object identity;
- input-list and selected-set immutability; and
- null/empty source lists.

Add focused tests for any pure summary/name-mapping helper extracted from the
Settings fragment. Keep Android `Locale` and `Collator` setup deterministic in
tests by supplying locales explicitly where the helper permits it.

Run the full repository gate because this change adds unit tests:

```sh
./scripts/check.sh --full
```

### Manual verification

1. Open **Settings → Video and audio → Caption languages** and confirm the
   checklist scrolls, sorts, and restores selections without opening a
   keyboard.
2. With no selection, play a multi-caption video and compare the popup with
   current behavior.
3. Select one language, load the video again, and confirm only its manual and
   auto-generated tracks remain. Add a second language and repeat.
4. Test regional tags where available and a selection with no matching track;
   confirm the caption button hides.
5. Clear all selections and confirm the complete popup returns on the next
   loaded video.
6. Change the setting during active playback and confirm there is no reload;
   verify the new set takes effect on the next loaded item.
7. Verify saved-caption selection after clearing the filter, auto-translation
   interaction, and unchanged download subtitles.
8. Exercise the Settings checklist with touch, TV/d-pad navigation, large
   fonts, and a non-English UI locale.

## 8. Explicit non-goals

- Search fields, freeform text, or any keyboard UI in ExoPlayer.
- Replacing or redesigning the existing player caption popup.
- Filtering downloads or mutating `StreamInfo`'s extracted subtitle list.
- Changing auto-generated or auto-translated subtitle extraction.
- Separate regional-variant selections, custom language names, or automatic
  preferred-caption selection.
- Immediate reload of an active video when Settings changes.
- Caption styling, saved-caption migration, or changes to **No Captions**.
