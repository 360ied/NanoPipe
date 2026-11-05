package org.schabi.newpipe.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.schabi.newpipe.R;
import org.schabi.newpipe.util.CaptionLanguageFilter;
import org.schabi.newpipe.util.CaptionLanguageFilter.LanguageEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable checklist for the caption-language allowlist. An empty selection
 * means all languages. The vocabulary is rebuilt on open and the stored set is
 * only rewritten on save.
 */
public class CaptionLanguageSelectionDialog extends DialogFragment {
    private static final String STATE_WORKING_SET = "working_set";
    private static final String STATE_QUERY = "query";
    private static final String STATE_SCROLL = "scroll";

    private List<LanguageEntry> vocabulary = new ArrayList<>();
    private final Set<String> workingSet = new HashSet<>();
    private LanguageSelectionAdapter adapter;
    private EditText searchField;
    private RecyclerView languageList;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final View view = getLayoutInflater()
                .inflate(R.layout.dialog_caption_language_selection, null);
        searchField = view.findViewById(R.id.caption_language_search);
        languageList = view.findViewById(R.id.caption_language_recycler_view);

        // ~200 locales + Collator sort; fast enough for dialog open on modern devices
        // and kept synchronous so the dialog and the settings summary agree.
        vocabulary = CaptionLanguageFilter.buildVocabulary(
                Locale.getDefault(),
                Arrays.asList(Locale.getAvailableLocales()));
        if (savedInstanceState != null) {
            final ArrayList<String> restored =
                    savedInstanceState.getStringArrayList(STATE_WORKING_SET);
            if (restored != null) {
                workingSet.addAll(restored);
            }
        } else {
            // getDefaultSharedPreferences() resolves to the same store as
            // VideoAudioSettingsFragment's getPreferenceManager().getSharedPreferences().
            workingSet.addAll(CaptionLanguageFilter.normalizeSelection(
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .getStringSet(getString(R.string.caption_language_filter_key),
                                    null)));
        }

        setupRecyclerView(view);
        setupSearch(view);
        setupButtons(view);
        if (savedInstanceState != null) {
            searchField.setText(savedInstanceState.getString(STATE_QUERY, ""));
            @SuppressWarnings("deprecation")
            final Parcelable scrollState =
                    savedInstanceState.getParcelable(STATE_SCROLL);
            if (scrollState != null) {
                languageList.post(() -> {
                    if (languageList.getLayoutManager() != null) {
                        languageList.getLayoutManager()
                                .onRestoreInstanceState(scrollState);
                    }
                });
            }
        }
        updateEmptyState(view);

        return new AlertDialog.Builder(requireContext())
                .setTitle(R.string.caption_language_filter_title)
                .setView(view)
                .create();
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putStringArrayList(STATE_WORKING_SET, new ArrayList<>(workingSet));
        // getView() is always null for a DialogFragment that only overrides
        // onCreateDialog(), so persist via the retained dialog views.
        if (searchField != null) {
            outState.putString(STATE_QUERY, searchField.getText().toString());
        }
        if (languageList != null && languageList.getLayoutManager() != null) {
            outState.putParcelable(STATE_SCROLL, languageList.getLayoutManager()
                    .onSaveInstanceState());
        }
    }

    private void setupRecyclerView(final View view) {
        final RecyclerView recyclerView =
                view.findViewById(R.id.caption_language_recycler_view);
        adapter = new LanguageSelectionAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
    }

    private void setupSearch(final View view) {
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start,
                                          final int count, final int after) {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start,
                                      final int before, final int count) {
                if (adapter != null) {
                    adapter.setFilter(s == null ? null : s.toString());
                    updateEmptyState(view);
                }
            }

            @Override
            public void afterTextChanged(final Editable s) {
            }
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                searchField.clearFocus();
            }
            return false;
        });
    }

    private void setupButtons(final View view) {
        final Button cancelButton = view.findViewById(R.id.cancel_button);
        final Button saveButton = view.findViewById(R.id.save_button);
        cancelButton.setOnClickListener(v -> dismiss());
        saveButton.setOnClickListener(v -> saveChanges());
    }

    private void saveChanges() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putStringSet(getString(R.string.caption_language_filter_key),
                        CaptionLanguageFilter.normalizeSelection(workingSet))
                .apply();
        dismiss();
    }

    private void updateEmptyState(@Nullable final View view) {
        if (view == null || adapter == null) {
            return;
        }
        final View emptyView = view.findViewById(R.id.caption_language_empty);
        emptyView.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private class LanguageSelectionAdapter
            extends RecyclerView.Adapter<LanguageViewHolder> {
        private List<LanguageEntry> visibleEntries = rebuild(null);
        private Set<String> visibleSelected = new HashSet<>(workingSet);

        void setFilter(@Nullable final String query) {
            final List<LanguageEntry> updated = rebuild(query);
            final Set<String> updatedSelected = new HashSet<>(workingSet);
            final DiffUtil.DiffResult diff = DiffUtil.calculateDiff(
                    new LanguageDiffCallback(visibleEntries, updated,
                            visibleSelected, updatedSelected));
            visibleEntries = updated;
            visibleSelected = updatedSelected;
            diff.dispatchUpdatesTo(this);
        }

        @Nullable
        LanguageEntry entryAt(final int position) {
            if (position < 0 || position >= visibleEntries.size()) {
                return null;
            }
            return visibleEntries.get(position);
        }

        private List<LanguageEntry> rebuild(@Nullable final String query) {
            // Selected-first on every rebuild (open, query change) so the
            // current selection surfaces on top. Toggling only rebinds the
            // row and never reshuffles the list mid-interaction.
            return CaptionLanguageFilter.sortSelectedFirst(
                    CaptionLanguageFilter.filterEntriesByQuery(vocabulary, query),
                    workingSet);
        }

        @NonNull
        @Override
        public LanguageViewHolder onCreateViewHolder(@NonNull final ViewGroup parent,
                                                    final int viewType) {
            final View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_caption_language_selection, parent, false);
            return new LanguageViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull final LanguageViewHolder holder,
                                    final int position) {
            holder.bind(visibleEntries.get(position));
        }

        @Override
        public int getItemCount() {
            return visibleEntries.size();
        }
    }

    private static class LanguageDiffCallback extends DiffUtil.Callback {
        private final List<LanguageEntry> oldEntries;
        private final List<LanguageEntry> newEntries;
        private final Set<String> oldSelected;
        private final Set<String> newSelected;

        LanguageDiffCallback(final List<LanguageEntry> oldEntries,
                             final List<LanguageEntry> newEntries,
                             final Set<String> oldSelected,
                             final Set<String> newSelected) {
            this.oldEntries = oldEntries;
            this.newEntries = newEntries;
            this.oldSelected = oldSelected;
            this.newSelected = newSelected;
        }

        @Override
        public int getOldListSize() {
            return oldEntries.size();
        }

        @Override
        public int getNewListSize() {
            return newEntries.size();
        }

        @Override
        public boolean areItemsTheSame(final int oldPosition, final int newPosition) {
            return oldEntries.get(oldPosition).code
                    .equals(newEntries.get(newPosition).code);
        }

        @Override
        public boolean areContentsTheSame(final int oldPosition, final int newPosition) {
            final LanguageEntry oldEntry = oldEntries.get(oldPosition);
            final LanguageEntry newEntry = newEntries.get(newPosition);
            // Checkbox state derives from the selection, not the entry: a row that
            // moved after a toggle must rebind even when code+name are unchanged.
            return oldEntry.code.equals(newEntry.code)
                    && oldEntry.name.equals(newEntry.name)
                    && oldSelected.contains(oldEntry.code)
                        == newSelected.contains(newEntry.code);
        }
    }

    private class LanguageViewHolder extends RecyclerView.ViewHolder {
        private final CheckBox checkBox;
        private final TextView nameView;

        LanguageViewHolder(@NonNull final View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.caption_language_checkbox);
            nameView = itemView.findViewById(R.id.caption_language_name);
            itemView.setOnClickListener(v -> {
                final int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                toggle(position);
            });
            checkBox.setOnClickListener(v -> {
                final int position = getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) {
                    return;
                }
                toggle(position);
            });
        }

        void bind(final LanguageEntry entry) {
            nameView.setText(entry.name);
            checkBox.setChecked(workingSet.contains(entry.code));
        }

        private void toggle(final int position) {
            final LanguageEntry entry =
                    adapter.entryAt(position);
            if (entry == null) {
                return;
            }
            if (workingSet.contains(entry.code)) {
                workingSet.remove(entry.code);
            } else {
                workingSet.add(entry.code);
            }
            adapter.notifyItemChanged(position);
        }
    }
}
