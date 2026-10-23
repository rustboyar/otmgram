package dev.otmgram.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

/** The app's settings file, shared by the repositories that keep their settings in it. */
internal val Context.settings by preferencesDataStore("settings")
