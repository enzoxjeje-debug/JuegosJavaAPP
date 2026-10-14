/*
 * Copyright 2024
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ru.playsoftware.j2meloader.library;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Global preferences of the game library. They are applied to the configuration
 * of every game right before it is launched, so a single switch affects the
 * whole collection.
 */
public class LibrarySettings {

	/** No scaling filter: raw pixels. */
	public static final int FILTER_OFF = 0;
	/** Bilinear filtering. */
	public static final int FILTER_SMOOTH = 1;
	/** Bilinear + bicubic "HD" shader with light sharpening. */
	public static final int FILTER_HD = 2;

	private static final String PREF_FILTER_MODE = "library.filterMode";
	private static final String PREF_FLUID_MODE = "library.fluidMode";
	private static final String PREF_SHOW_FPS = "library.showFps";
	private static final String PREF_ORIENTATION = "library.orientation";
	private static final String PREF_JOYSTICK = "library.joystick";
	private static final String PREF_VIEW_MODE = "library.viewMode";
	private static final String PREF_FAVORITES = "library.favorites";
	private static final String PREF_RECENTS = "library.recents";
	private static final int MAX_RECENTS = 50;

	private final SharedPreferences prefs;

	public LibrarySettings(Context context) {
		prefs = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
	}

	public int getFilterMode() {
		return prefs.getInt(PREF_FILTER_MODE, FILTER_HD);
	}

	public void setFilterMode(int mode) {
		prefs.edit().putInt(PREF_FILTER_MODE, mode).apply();
	}

	/** Fluid mode: no artificial FPS cap, immediate event delivery, parallel redraw. */
	public boolean getFluidMode() {
		return prefs.getBoolean(PREF_FLUID_MODE, true);
	}

	public void setFluidMode(boolean enabled) {
		prefs.edit().putBoolean(PREF_FLUID_MODE, enabled).apply();
	}

	public boolean getShowFps() {
		return prefs.getBoolean(PREF_SHOW_FPS, false);
	}

	public void setShowFps(boolean show) {
		prefs.edit().putBoolean(PREF_SHOW_FPS, show).apply();
	}

	/** 0 = auto, 1 = portrait, 2 = landscape. */
	public int getOrientation() {
		return prefs.getInt(PREF_ORIENTATION, 1);
	}

	public void setOrientation(int orientation) {
		prefs.edit().putInt(PREF_ORIENTATION, orientation).apply();
	}

	/** Game list display: one row per game. */
	public static final int VIEW_LIST = 0;
	/** Game list display: icon grid. */
	public static final int VIEW_GRID = 1;

	/** true = analog joystick overlay, false = classic virtual phone keypad. */
	public boolean getJoystick() {
		return prefs.getBoolean(PREF_JOYSTICK, true);
	}

	public void setJoystick(boolean joystick) {
		prefs.edit().putBoolean(PREF_JOYSTICK, joystick).apply();
	}

	/** Library display mode: {@link #VIEW_LIST} or {@link #VIEW_GRID}. */
	public int getViewMode() {
		return prefs.getInt(PREF_VIEW_MODE, VIEW_GRID);
	}

	public void setViewMode(int mode) {
		prefs.edit().putInt(PREF_VIEW_MODE, mode).apply();
	}

	/** Whether the game is marked as favorite. */
	public boolean isFavorite(String gameId) {
		return prefs.getStringSet(PREF_FAVORITES, Collections.emptySet()).contains(gameId);
	}

	/** Adds/removes the game from favorites; returns true when it became a favorite. */
	public boolean toggleFavorite(String gameId) {
		Set<String> favorites = new LinkedHashSet<>(
				prefs.getStringSet(PREF_FAVORITES, Collections.emptySet()));
		boolean added;
		if (favorites.remove(gameId)) {
			added = false;
		} else {
			favorites.add(gameId);
			added = true;
		}
		prefs.edit().putStringSet(PREF_FAVORITES, favorites).apply();
		return added;
	}

	/** Game ids ordered by last play, newest first. */
	public List<String> getRecents() {
		try {
			List<String> recents = new Gson().fromJson(prefs.getString(PREF_RECENTS, "[]"),
					new TypeToken<List<String>>() {
					}.getType());
			return recents != null ? recents : new ArrayList<>();
		} catch (Exception e) {
			return new ArrayList<>();
		}
	}

	/** Moves the game to the front of the recently played list. */
	public void addRecent(String gameId) {
		List<String> recents = getRecents();
		recents.remove(gameId);
		recents.add(0, gameId);
		if (recents.size() > MAX_RECENTS) {
			recents.subList(MAX_RECENTS, recents.size()).clear();
		}
		prefs.edit().putString(PREF_RECENTS, new Gson().toJson(recents)).apply();
	}
}
