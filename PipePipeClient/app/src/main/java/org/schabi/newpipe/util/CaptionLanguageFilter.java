package org.schabi.newpipe.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.SubtitlesStream;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Primary-language matching for the caption-language allowlist.
 *
 * <p>All methods are pure: they never touch {@code SharedPreferences} and never
 * mutate their inputs. Callers pass in the stored string set and the subtitle
 * list and receive new, independent results.</p>
 */
public final class CaptionLanguageFilter {

    /**
     * Codes that must exist in the checklist even if the platform does not
     * report a locale for them.
     */
    private static final String[] EXTRA_CODES = {"yue", "ceb", "haw"};

    private CaptionLanguageFilter() {
    }

    /**
     * A checklist row: canonical primary code plus its localized display name.
     */
    public static final class LanguageEntry {
        @NonNull
        public final String code;
        @NonNull
        public final String name;

        public LanguageEntry(@NonNull final String code, @NonNull final String name) {
            this.code = Objects.requireNonNull(code);
            this.name = Objects.requireNonNull(name);
        }
    }

    /**
     * Reduce any language tag to its canonical primary subtag.
     *
     * <p>Handles regional variants ({@code en-US}), underscore separators
     * ({@code en_US}), mixed case, and the legacy aliases {@code iw}/{@code in}/
     * {@code ji} (mapped to {@code he}/{@code id}/{@code yi}, matching their
     * modern equivalents). The undetermined tag {@code und} maps to empty
     * because it names no language and can never be selected.
     * Returns an empty string for null, blank, or
     * unparseable tags.</p>
     */
    @NonNull
    public static String canonicalPrimaryCode(@Nullable final String tag) {
        if (tag == null) {
            return "";
        }
        final String normalized = tag.trim().replace('_', '-');
        if (normalized.isEmpty()) {
            return "";
        }
        final int dash = normalized.indexOf('-');
        final String primary = (dash == -1 ? normalized : normalized.substring(0, dash))
                .toLowerCase(Locale.ROOT);
        if (primary.isEmpty()) {
            return "";
        }
        switch (primary) {
            case "iw":
                return "he";
            case "in":
                return "id";
            case "ji":
                return "yi";
            case "und":
                return "";
            default:
                return primary;
        }
    }

    /**
     * Normalize a stored preference set to canonical primary codes.
     *
     * @return a new set; never null, never the passed-in instance
     */
    @NonNull
    public static Set<String> normalizeSelection(@Nullable final Set<String> selected) {
        final Set<String> normalized = new HashSet<>();
        if (selected == null) {
            return normalized;
        }
        for (final String code : selected) {
            final String canonical = canonicalPrimaryCode(code);
            if (!canonical.isEmpty()) {
                normalized.add(canonical);
            }
        }
        return normalized;
    }

    /**
     * Check a single language tag against an already-normalized selection.
     * An empty selection allows everything.
     *
     * @param normalizedSelection selection previously passed through
     *                              {@link #normalizeSelection}; raw stored sets
     *                              must be normalized first or non-canonical
     *                              codes will never match
     */
    public static boolean isTagAllowed(@NonNull final Set<String> normalizedSelection,
                                      @Nullable final String tag) {
        Objects.requireNonNull(normalizedSelection);
        if (normalizedSelection.isEmpty()) {
            return true;
        }
        final String primary = canonicalPrimaryCode(tag);
        return !primary.isEmpty() && normalizedSelection.contains(primary);
    }

    /**
     * Filter side-loaded subtitles by the selected primary-language codes.
     *
     * @return the matching streams in their original order as new, independent
     *         list holding the original objects; an absent or empty selection
     *         returns every non-null input stream in a new list
     */
    @NonNull
    public static List<SubtitlesStream> filterSubtitles(
            @Nullable final Set<String> selected,
            @Nullable final List<SubtitlesStream> subtitles) {
        final List<SubtitlesStream> result = new ArrayList<>();
        if (subtitles == null) {
            return result;
        }
        final Set<String> normalized = normalizeSelection(selected);
        if (normalized.isEmpty()) {
            for (final SubtitlesStream stream : subtitles) {
                if (stream != null) {
                    result.add(stream);
                }
            }
            return result;
        }
        for (final SubtitlesStream stream : subtitles) {
            if (stream != null && isTagAllowed(normalized, stream.getLanguageTag())) {
                result.add(stream);
            }
        }
        return result;
    }

    /**
     * Primary subtag of a platform locale, lowercased.
     *
     * <p>The result is not vocabulary-safe on its own: pass it through
     * {@link #canonicalPrimaryCode} to fold legacy aliases before use.</p>
     */
    @NonNull
    public static String primaryCodeOf(@Nullable final Locale locale) {
        if (locale == null) {
            return "";
        }
        final String tag = locale.toLanguageTag();
        final int dash = tag.indexOf('-');
        final String primary = (dash == -1 ? tag : tag.substring(0, dash))
                .toLowerCase(Locale.ROOT);
        // toLanguageTag() returns "und" for locales without a language.
        if (primary.isEmpty() || "und".equals(primary)) {
            return "";
        }
        return primary;
    }

    private static boolean isVocabularyCode(final String code) {
        if (code.length() < 2 || code.length() > 3) {
            return false;
        }
        for (int i = 0; i < code.length(); i++) {
            final char c = code.charAt(i);
            if (c < 'a' || c > 'z') {
                return false;
            }
        }
        return true;
    }

    /**
     * Build the checklist vocabulary: deduplicated canonical codes with
     * localized display names, sorted with the display locale's collator.
     *
     * @param displayLocale   UI locale used for names and sorting
     * @param availableLocales platform locales to derive codes from; callers
     *                         pass {@code Locale.getAvailableLocales()}
     * @return sorted entries; always includes {@code yue}, {@code ceb},
     *         and {@code haw}
     */
    @NonNull
    public static List<LanguageEntry> buildVocabulary(
            @NonNull final Locale displayLocale,
            @Nullable final Collection<Locale> availableLocales) {
        Objects.requireNonNull(displayLocale);
        // Keyed by code so aliases that canonicalize to the same value
        // (e.g. iw/he) collapse into a single entry.
        final Map<String, String> codeToName = new TreeMap<>();
        if (availableLocales != null) {
            for (final Locale locale : availableLocales) {
                final String code = canonicalPrimaryCode(primaryCodeOf(locale));
                if (isVocabularyCode(code)) {
                    codeToName.putIfAbsent(code, displayNameFor(code, displayLocale));
                }
            }
        }
        for (final String extra : EXTRA_CODES) {
            codeToName.putIfAbsent(extra, displayNameFor(extra, displayLocale));
        }
        final List<LanguageEntry> entries = new ArrayList<>(codeToName.size());
        for (final Map.Entry<String, String> entry : codeToName.entrySet()) {
            entries.add(new LanguageEntry(entry.getKey(), entry.getValue()));
        }
        final Collator collator = Collator.getInstance(displayLocale);
        Collections.sort(entries, (a, b) -> {
            final int cmp = collator.compare(a.name, b.name);
            return cmp != 0 ? cmp : a.code.compareTo(b.code);
        });
        return entries;
    }

    /**
     * Localized display name for a canonical code, falling back to the
     * uppercase code when the platform has no name for it.
     */
    @NonNull
    public static String displayNameFor(@NonNull final String code,
                                       @NonNull final Locale displayLocale) {
        Objects.requireNonNull(code);
        Objects.requireNonNull(displayLocale);
        final String name = new Locale(code).getDisplayLanguage(displayLocale);
        if (name == null || name.isEmpty() || name.equals(code)) {
            return code.toUpperCase(Locale.ROOT);
        }
        return name;
    }

    /**
     * Localized, sorted display names for the selected codes, for short
     * summaries. Unknown codes fall back to their uppercase form.
     */
    @NonNull
    public static List<String> sortedDisplayNames(
            @Nullable final Set<String> normalizedSelected,
            @NonNull final Map<String, String> codeToName,
            @NonNull final Collator collator) {
        Objects.requireNonNull(codeToName);
        Objects.requireNonNull(collator);
        final List<String> names = new ArrayList<>();
        if (normalizedSelected == null) {
            return names;
        }
        for (final String code : normalizedSelected) {
            final String name = codeToName.get(code);
            names.add(name != null ? name : code.toUpperCase(Locale.ROOT));
        }
        Collections.sort(names, collator);
        return names;
    }

    /**
     * Filter vocabulary entries by a user-typed query, matching localized
     * display names or canonical codes as a raw case-insensitive substring
     * (the query is not canonicalized, so a regional query never matches a
     * primary code). A null or ASCII-blank query returns every entry in
     * order.
     */
    @NonNull
    public static List<LanguageEntry> filterEntriesByQuery(
            @NonNull final List<LanguageEntry> vocabulary,
            @Nullable final String query) {
        Objects.requireNonNull(vocabulary);
        final String needle =
                query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        final List<LanguageEntry> result = new ArrayList<>(vocabulary.size());
        for (final LanguageEntry entry : vocabulary) {
            if (entry == null) {
                continue;
            }
            if (needle.isEmpty()
                    || entry.name.toLowerCase(Locale.ROOT).contains(needle)
                    || entry.code.contains(needle)) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * Stable selected-first ordering: selected entries keep their relative
     * order first, followed by the rest in their relative order. Used so a
     * reopened checklist surfaces the current selection without reshuffling
     * rows on toggle (order recomputes on open and on query change).
     *
     * @param normalizedSelected selection previously passed through
     *                             {@link #normalizeSelection}; raw stored sets
     *                             must be normalized first or non-canonical
     *                             codes will never match
     */
    @NonNull
    public static List<LanguageEntry> sortSelectedFirst(
            @NonNull final List<LanguageEntry> entries,
            @Nullable final Set<String> normalizedSelected) {
        Objects.requireNonNull(entries);
        final List<LanguageEntry> result = new ArrayList<>(entries.size());
        final List<LanguageEntry> rest = new ArrayList<>(entries.size());
        for (final LanguageEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (normalizedSelected != null
                    && normalizedSelected.contains(entry.code)) {
                result.add(entry);
            } else {
                rest.add(entry);
            }
        }
        result.addAll(rest);
        return result;
    }

    /**
     * Code-to-name lookup for a vocabulary, for summary rendering.
     */
    @NonNull
    public static Map<String, String> nameMap(
            @NonNull final List<LanguageEntry> vocabulary) {
        Objects.requireNonNull(vocabulary);
        final Map<String, String> map = new HashMap<>(vocabulary.size() + 1);
        for (final LanguageEntry entry : vocabulary) {
            if (entry != null) {
                map.put(entry.code, entry.name);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
