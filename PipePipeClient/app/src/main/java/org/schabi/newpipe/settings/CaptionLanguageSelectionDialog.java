package org.schabi.newpipe.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.PreferenceManager;
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

    @NonNull
    @Override
    @SuppressWarnings("deprecation")
    public Dialog onCreateDialog(@Nullable final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_caption_language_selection, null);

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
            workingSet.addAll(CaptionLanguageFilter.normalizeSelection(
                    PreferenceManager.getDefaultSharedPreferences(requireContext())
                            .getStringSet(getString(R.string.caption_language_filter_key),
                                    null)));
        }

        setupRecyclerView(view);
        setupSearch(view);
        setupButtons(view);
        if (savedInstanceState != null) {
            final EditText searchField =
                    view.findViewById(R.id.caption_language_search);
            searchField.setText(savedInstanceState.getString(STATE_QUERY, ""));
            final android.os.Parcelable scrollState =
                    savedInstanceState.getParcelable(STATE_SCROLL);
            if (scrollState != null) {
                final RecyclerView recyclerView = view.findViewById(
                        R.id.caption_language_recycler_view);
                recyclerView.post(() -> {
                    if (recyclerView.getLayoutManager() != null) {
                        recyclerView.getLayoutManager()
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
        final View view = getView();
        if (view != null) {
            final EditText searchField =
                    view.findViewById(R.id.caption_language_search);
            final RecyclerView recyclerView =
                    view.findViewById(R.id.caption_language_recycler_view);
            outState.putString(STATE_QUERY, searchField.getText().toString());
            if (recyclerView.getLayoutManager() != null) {
                outState.putParcelable(STATE_SCROLL, recyclerView.getLayoutManager()
                        .onSaveInstanceState());
            }
        }
    }

    private void setupRecyclerView(final View view) {
        final RecyclerView recyclerView =
                view.findViewById(R.id.caption_language_recycler_view);
        adapter = new LanguageSelectionAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);
        updateEmptyState(view);
    }

    private void setupSearch(final View view) {
        final EditText searchField = view.findViewById(R.id.caption_language_search);
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
                    updateEmptyState(getView());
                }
            }

            @Override
            public void afterTextChanged(final Editable s) {
            }
        });
    }

    private void setupButtons(final View view) {
        final Button cancelButton = view.findViewById(R.id.cancel_button);
        final Button saveButton = view.findViewById(R.id.save_button);
        cancelButton.setOnClickListener(v -> dismissAllowingStateLoss());
        saveButton.setOnClickListener(v -> saveChanges());
    }

    private void saveChanges() {
        PreferenceManager.getDefaultSharedPreferences(requireContext())
                .edit()
                .putStringSet(getString(R.string.caption_language_filter_key),
                        CaptionLanguageFilter.normalizeSelection(workingSet))
                .apply();
        dismissAllowingStateLoss();
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

        void setFilter(@Nullable final String query) {
            visibleEntries = rebuild(query);
            notifyDataSetChanged();
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
            checkBox.setContentDescription(entry.name);
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
