package org.schabi.newpipe.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;

import com.google.android.material.snackbar.Snackbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.util.CaptionLanguageFilter;
import org.schabi.newpipe.util.CaptionLanguageFilter.LanguageEntry;
import org.schabi.newpipe.util.CodecSupportDetector;
import org.schabi.newpipe.util.PermissionHelper;

import java.text.Collator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.disposables.CompositeDisposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class VideoAudioSettingsFragment extends BasePreferenceFragment {
    /** Dialog row indices: keep label, result, and checked state in lockstep. */
    private static final int IDX_VP9 = 0;
    private static final int IDX_AV1 = 1;

    /** Maximum selected languages listed by name before the summary collapses to a count. */
    private static final int MAX_NAMES_IN_SUMMARY = 3;

    private List<LanguageEntry> captionLanguageVocabulary;
    private Map<String, String> captionLanguageCodeToName;

    private SharedPreferences.OnSharedPreferenceChangeListener listener;
    private final CompositeDisposable disposables = new CompositeDisposable();

    @Override
    public void onCreatePreferences(@Nullable final Bundle savedInstanceState,
                                    @Nullable final String rootKey) {
        addPreferencesFromResourceRegistry();

        listener = (sharedPreferences, s) -> {
            if (s.equals(getString(R.string.caption_language_filter_key))) {
                final Preference changed = findPreference(s);
                if (changed instanceof MultiSelectListPreference) {
                    updateCaptionLanguageFilterSummary((MultiSelectListPreference) changed);
                }
            }
            if (s.equals(getString(R.string.minimize_on_exit_key))) {
                final String newSetting = sharedPreferences.getString(s, null);
                if (newSetting != null
                        && newSetting.equals(getString(R.string.minimize_on_exit_popup_key))
                        && !Settings.canDrawOverlays(getContext())) {

                    Snackbar.make(getListView(), R.string.permission_display_over_apps,
                                    Snackbar.LENGTH_INDEFINITE)
                            .setAction(R.string.settings, view ->
                                    PermissionHelper.checkSystemAlertWindowPermission(getContext()))
                            .show();

                }
            }
        };

        // The registry maps this fragment to exactly one static XML, so a missing key
        // can only be a wiring typo: fail fast via the codebase baseline.
        requirePreference(R.string.detect_codecs_key)
                .setOnPreferenceClickListener(preference -> {
                    runDetection(preference);
                    return true;
                });

        setupCaptionLanguageFilter();
    }

    /**
     * Populate the caption-language checklist vocabulary once and refresh its
     * summary. No stored selection (empty set) means all languages.
     */
    private void setupCaptionLanguageFilter() {
        final Preference preference =
                requirePreference(R.string.caption_language_filter_key);
        if (!(preference instanceof MultiSelectListPreference)) {
            throw new IllegalStateException(
                    "caption_language_filter_key must be a MultiSelectListPreference");
        }
        final MultiSelectListPreference filterPreference =
                (MultiSelectListPreference) preference;
        captionLanguageVocabulary = CaptionLanguageFilter.buildVocabulary(
                Locale.getDefault(),
                Arrays.asList(Locale.getAvailableLocales()));
        captionLanguageCodeToName =
                CaptionLanguageFilter.nameMap(captionLanguageVocabulary);
        final CharSequence[] entries =
                new CharSequence[captionLanguageVocabulary.size()];
        final CharSequence[] entryValues =
                new CharSequence[captionLanguageVocabulary.size()];
        for (int i = 0; i < captionLanguageVocabulary.size(); i++) {
            final LanguageEntry entry = captionLanguageVocabulary.get(i);
            entries[i] = entry.name;
            entryValues[i] = entry.code;
        }
        filterPreference.setEntries(entries);
        filterPreference.setEntryValues(entryValues);
        // Drop codes that no longer match the vocabulary (e.g. restored from
        // a backup) so the dialog checkboxes reflect the effective filter.
        final Set<String> normalizedStored =
                CaptionLanguageFilter.normalizeSelection(filterPreference.getValues());
        if (!normalizedStored.equals(filterPreference.getValues())) {
            filterPreference.setValues(normalizedStored);
        }
        updateCaptionLanguageFilterSummary(filterPreference);
    }

    private void updateCaptionLanguageFilterSummary(
            final MultiSelectListPreference preference) {
        if (!isAdded() || captionLanguageVocabulary == null
                || captionLanguageCodeToName == null) {
            return;
        }
        final Set<String> selected = CaptionLanguageFilter.normalizeSelection(
                preference.getValues());
        if (selected.isEmpty()) {
            preference.setSummary(R.string.caption_language_filter_all);
            return;
        }
        final List<String> names = CaptionLanguageFilter.sortedDisplayNames(
                selected, captionLanguageCodeToName,
                Collator.getInstance(Locale.getDefault()));
        if (names.size() <= MAX_NAMES_IN_SUMMARY) {
            preference.setSummary(TextUtils.join(
                    getString(R.string.caption_language_filter_list_separator), names));
        } else {
            preference.setSummary(getResources().getQuantityString(
                    R.plurals.caption_language_filter_count, names.size(), names.size()));
        }
    }

    /**
     * Runs the codec probe off the UI thread and presents the outcome in a confirm dialog.
     * Nothing is written until the user presses Apply; re-running never modifies the
     * stored set by itself.
     */
    private void runDetection(final Preference detectPreference) {
        detectPreference.setEnabled(false);
        detectPreference.setSummary(R.string.detect_codecs_running);
        disposables.add(Single.fromCallable(CodecSupportDetector::detectSync)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(results -> {
                    if (!isAdded()) {
                        return;
                    }
                    detectPreference.setEnabled(true);
                    detectPreference.setSummary(R.string.detect_codecs_summary);
                    showDetectResultDialog(results);
                }, error -> {
                    if (!isAdded()) {
                        return;
                    }
                    detectPreference.setEnabled(true);
                    detectPreference.setSummary(R.string.detect_codecs_summary);
                    Log.w(TAG, "Codec detection failed", error);
                    ErrorUtil.showUiErrorSnackbar(this, "Detecting codecs", error);
                }));
    }

    private void showDetectResultDialog(
            final Map<String, CodecSupportDetector.DetectionResult> results) {
        final CharSequence[] items = new CharSequence[IDX_AV1 + 1];
        items[IDX_VP9] = rowLabel(CodecSupportDetector.TOGGLE_VP9,
                results.get(CodecSupportDetector.TOGGLE_VP9));
        items[IDX_AV1] = rowLabel(CodecSupportDetector.TOGGLE_AV1,
                results.get(CodecSupportDetector.TOGGLE_AV1));
        final boolean[] checked = new boolean[IDX_AV1 + 1];
        checked[IDX_VP9] = CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_VP9));
        checked[IDX_AV1] = CodecSupportDetector.isChecked(
                results.get(CodecSupportDetector.TOGGLE_AV1));
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.codec_detect_dialog_title)
                .setMultiChoiceItems(items, checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.codec_detect_apply, (dialog, which) -> {
                    final SharedPreferences prefs = PreferenceManager
                            .getDefaultSharedPreferences(requireContext());
                    final String formatsKey = getString(R.string.advanced_formats_key);
                    final Set<String> applied = CodecSupportDetector.applyToStoredSet(
                            checked[IDX_VP9], checked[IDX_AV1]);
                    prefs.edit()
                            .putStringSet(formatsKey, applied)
                            // An explicit Apply retires the first-run seed, so a late
                            // probe can never overwrite this choice afterwards.
                            .putBoolean(getString(R.string.codec_seed_done_key), true)
                            .apply();
                    Log.i(TAG, "Codec detection applied advanced formats: " + applied);
                    Toast.makeText(requireContext(), R.string.codec_detect_applied,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String rowLabel(final String toggle,
                            final CodecSupportDetector.DetectionResult result) {
        if (CodecSupportDetector.shows60FpsAdvisory(result)) {
            return getString(R.string.codec_detect_row_hw_limited, toggle);
        }
        if (result != null
                && result.getSupport() == CodecSupportDetector.Support.SUPPORTED_HW) {
            return getString(R.string.codec_detect_row_hw, toggle);
        }
        if (result != null
                && result.getSupport() == CodecSupportDetector.Support.SUPPORTED_SW_ONLY) {
            return getString(R.string.codec_detect_row_sw_only, toggle);
        }
        return getString(R.string.codec_detect_row_unsupported, toggle);
    }

    @Override
    public boolean onPreferenceTreeClick(final Preference preference) {
        if (getString(R.string.caption_settings_key).equals(preference.getKey())) {
            try {
                startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
            } catch (final ActivityNotFoundException e) {
                Toast.makeText(getActivity(), R.string.general_error, Toast.LENGTH_SHORT).show();
            }
        }

        return super.onPreferenceTreeClick(preference);
    }

    @Override
    public void onResume() {
        super.onResume();
        getPreferenceManager().getSharedPreferences()
                .registerOnSharedPreferenceChangeListener(listener);
        final Preference preference =
                findPreference(getString(R.string.caption_language_filter_key));
        if (preference instanceof MultiSelectListPreference) {
            updateCaptionLanguageFilterSummary((MultiSelectListPreference) preference);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        getPreferenceManager().getSharedPreferences()
                .unregisterOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void onDestroy() {
        disposables.clear();
        super.onDestroy();
    }
}
