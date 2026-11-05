package org.schabi.newpipe.settings;

import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import org.schabi.newpipe.MainActivity;
import org.schabi.newpipe.util.ThemeHelper;

import java.util.Objects;

public abstract class BasePreferenceFragment extends PreferenceFragmentCompat {
    protected final String TAG = getClass().getSimpleName() + "@" + Integer.toHexString(hashCode());
    protected static final boolean DEBUG = MainActivity.DEBUG;

    SharedPreferences defaultPreferences;

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        defaultPreferences = PreferenceManager.getDefaultSharedPreferences(requireActivity());
        super.onCreate(savedInstanceState);
    }

    protected void addPreferencesFromResourceRegistry() {
        addPreferencesFromResource(
                SettingsResourceRegistry.getInstance().getPreferencesResId(this.getClass()));
    }

    @Override
    public void onViewCreated(@NonNull final View rootView,
                              @Nullable final Bundle savedInstanceState) {
        super.onViewCreated(rootView, savedInstanceState);
        setDivider(null);
        ThemeHelper.setTitleToAppCompatActivity(getActivity(), getPreferenceScreen().getTitle());
    }

    @Override
    public void onResume() {
        super.onResume();
        ThemeHelper.setTitleToAppCompatActivity(getActivity(), getPreferenceScreen().getTitle());
    }

    @NonNull
    protected final Preference requirePreference(@StringRes final int resId) {
        final String entryName;
        final String key;
        try {
            entryName = getResources().getResourceEntryName(resId);
            key = getString(resId);
        } catch (final Resources.NotFoundException e) {
            throw new IllegalArgumentException(
                    "Missing preference for invalid resId=" + resId, e);
        }
        final Preference preference = findPreference(key);
        final String message =
                "Missing preference for key '" + key + "' (" + entryName + ")";
        return Objects.requireNonNull(preference, () -> message);
    }
}
