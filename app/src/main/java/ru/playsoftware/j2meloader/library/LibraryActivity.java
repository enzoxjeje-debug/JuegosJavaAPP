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

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ru.playsoftware.j2meloader.R;

/**
 * Launcher screen: lists every game bundled in the APK and starts it with the
 * emulator. The first run of a game extracts and converts it (a few seconds),
 * later runs start immediately.
 */
public class LibraryActivity extends AppCompatActivity {

	private static final int TAB_ALL = 0;
	private static final int TAB_RECENT = 1;
	private static final int TAB_FAVORITES = 2;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final ExecutorService worker = Executors.newFixedThreadPool(2);
	private final ExecutorService gameLoader = Executors.newSingleThreadExecutor();
	private final LruCache<String, Bitmap> iconCache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
		@Override
		protected int sizeOf(String key, Bitmap value) {
			return value.getByteCount();
		}
	};

	private List<GameEntry> allGames = new ArrayList<>();
	private final List<GameEntry> visibleGames = new ArrayList<>();
	private GameAdapter adapter;
	private TextView tvStatus;
	private GridView gridView;
	private ListView listView;
	private Button btnView;
	private EditText etSearch;
	private TextView tabAll;
	private TextView tabRecent;
	private TextView tabFavorites;
	private int currentTab = TAB_ALL;
	private LibrarySettings libSettings;
	private int viewMode;
	private volatile boolean busy;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_library);
		// the screen has its own header; the system ActionBar would only repeat it
		if (getSupportActionBar() != null) {
			getSupportActionBar().hide();
		}

		gridView = findViewById(R.id.gridGames);
		listView = findViewById(R.id.listGames);
		etSearch = findViewById(R.id.etSearch);
		tvStatus = findViewById(R.id.tvStatus);
		Button btnSettings = findViewById(R.id.btnSettings);
		btnView = findViewById(R.id.btnView);
		tabAll = findViewById(R.id.tabAll);
		tabRecent = findViewById(R.id.tabRecent);
		tabFavorites = findViewById(R.id.tabFavorites);
		libSettings = new LibrarySettings(this);
		viewMode = libSettings.getViewMode();

		adapter = new GameAdapter();
		gridView.setAdapter(adapter);
		listView.setAdapter(adapter);
		AdapterView.OnItemClickListener clickListener =
				(parent, view, position, id) -> openGame(visibleGames.get(position));
		AdapterView.OnItemLongClickListener longClickListener =
				(parent, view, position, id) -> {
					showGameOptions(visibleGames.get(position));
					return true;
				};
		gridView.setOnItemClickListener(clickListener);
		listView.setOnItemClickListener(clickListener);
		gridView.setOnItemLongClickListener(longClickListener);
		listView.setOnItemLongClickListener(longClickListener);
		tabAll.setOnClickListener(v -> selectTab(TAB_ALL));
		tabRecent.setOnClickListener(v -> selectTab(TAB_RECENT));
		tabFavorites.setOnClickListener(v -> selectTab(TAB_FAVORITES));
		updateTabs();
		applyViewMode(false);

		etSearch.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
				filter(s.toString());
			}

			@Override
			public void afterTextChanged(Editable s) {
			}
		});

		btnSettings.setOnClickListener(v -> showSettings());
		btnView.setOnClickListener(v -> {
			viewMode = (viewMode == LibrarySettings.VIEW_GRID)
					? LibrarySettings.VIEW_LIST : LibrarySettings.VIEW_GRID;
			libSettings.setViewMode(viewMode);
			applyViewMode(true);
		});

		tvStatus.setText(R.string.library_loading);
		gameLoader.execute(() -> {
			List<GameEntry> games = loadIndex();
			mainHandler.post(() -> {
				allGames = games;
				filter("");
				String autoId = getIntent().getStringExtra("gameId");
				if (autoId != null) {
					for (GameEntry game : allGames) {
						if (autoId.equals(game.id)) {
							openGame(game);
							break;
						}
					}
				}
			});
		});
	}

	private List<GameEntry> loadIndex() {
		try (Reader reader = new InputStreamReader(
				getAssets().open("games/index.json"), StandardCharsets.UTF_8)) {
			List<GameEntry> games = new Gson().fromJson(reader,
					new TypeToken<List<GameEntry>>() {
					}.getType());
			return games != null ? games : new ArrayList<>();
		} catch (IOException e) {
			e.printStackTrace();
			return new ArrayList<>();
		}
	}

	private void filter(String query) {
		visibleGames.clear();
		String q = query.trim().toLowerCase(Locale.ROOT);
		if (currentTab == TAB_RECENT) {
			for (String id : libSettings.getRecents()) {
				GameEntry game = findGame(id);
				if (game != null && matches(game, q)) {
					visibleGames.add(game);
				}
			}
		} else if (currentTab == TAB_FAVORITES) {
			for (GameEntry game : allGames) {
				if (libSettings.isFavorite(game.id) && matches(game, q)) {
					visibleGames.add(game);
				}
			}
		} else {
			for (GameEntry game : allGames) {
				if (matches(game, q)) {
					visibleGames.add(game);
				}
			}
		}
		adapter.notifyDataSetChanged();
		if (visibleGames.isEmpty() && q.isEmpty()) {
			if (currentTab == TAB_RECENT) {
				tvStatus.setText(R.string.library_no_recents);
				return;
			} else if (currentTab == TAB_FAVORITES) {
				tvStatus.setText(R.string.library_no_favorites);
				return;
			}
		}
		tvStatus.setText(getString(R.string.library_count, visibleGames.size(), allGames.size()));
	}

	private boolean matches(GameEntry game, String q) {
		return q.isEmpty()
				|| game.title.toLowerCase(Locale.ROOT).contains(q)
				|| (game.vendor != null && game.vendor.toLowerCase(Locale.ROOT).contains(q));
	}

	private GameEntry findGame(String id) {
		for (GameEntry game : allGames) {
			if (id.equals(game.id)) {
				return game;
			}
		}
		return null;
	}

	private void selectTab(int tab) {
		currentTab = tab;
		updateTabs();
		filter(etSearch.getText().toString());
	}

	private void updateTabs() {
		TextView[] tabs = {tabAll, tabRecent, tabFavorites};
		int[] ids = {TAB_ALL, TAB_RECENT, TAB_FAVORITES};
		for (int i = 0; i < tabs.length; i++) {
			boolean active = currentTab == ids[i];
			tabs[i].setBackgroundResource(
					active ? R.drawable.bg_tab_active : R.drawable.bg_tab_inactive);
			tabs[i].setTextColor(active
					? ContextCompat.getColor(this, android.R.color.white)
					: ContextCompat.getColor(this, R.color.text_secondary));
		}
	}

	/** Long-press options: toggle the favorite mark of the game. */
	private void showGameOptions(GameEntry game) {
		boolean favorite = libSettings.isFavorite(game.id);
		String[] options = {getString(favorite ? R.string.library_fav_remove : R.string.library_fav_add)};
		new AlertDialog.Builder(this)
				.setTitle(game.title)
				.setItems(options, (dialog, which) -> {
					libSettings.toggleFavorite(game.id);
					filter(etSearch.getText().toString());
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Switches the game browser between icon grid and one-row-per-game list. */
	private void applyViewMode(boolean animate) {
		View shown = listView;
		View hidden = gridView;
		if (viewMode == LibrarySettings.VIEW_GRID) {
			shown = gridView;
			hidden = listView;
			btnView.setText(R.string.library_view_list);
		} else {
			btnView.setText(R.string.library_view_grid);
		}
		hidden.setVisibility(View.GONE);
		shown.setVisibility(View.VISIBLE);
		adapter.notifyDataSetChanged();
		if (animate) {
			shown.startAnimation(AnimationUtils.loadAnimation(this, android.R.anim.fade_in));
		}
	}

	private void openGame(GameEntry game) {
		if (busy) {
			return;
		}
		busy = true;
		boolean firstRun = !GameInstaller.isPrepared(game);
		ProgressDialog dialog = new ProgressDialog(this);
		dialog.setMessage(getString(firstRun ? R.string.library_preparing : R.string.library_starting,
				game.title));
		dialog.setIndeterminate(true);
		dialog.setCancelable(false);
		dialog.show();

		gameLoader.execute(() -> {
			try {
				GameInstaller.prepare(getApplicationContext(), game);
				mainHandler.post(dialog::dismiss);
				libSettings.addRecent(game.id);
				GameInstaller.launch(getApplicationContext(), game);
			} catch (Throwable e) {
				e.printStackTrace();
				mainHandler.post(() -> {
					dialog.dismiss();
					new AlertDialog.Builder(LibraryActivity.this)
							.setTitle(R.string.library_error)
							.setMessage(game.title + "\n\n" + e)
							.setPositiveButton(android.R.string.ok, null)
							.show();
				});
			} finally {
				busy = false;
			}
		});
	}

	private void showSettings() {
		View view = LayoutInflater.from(this).inflate(R.layout.dialog_library_settings, null);
		LibrarySettings settings = new LibrarySettings(this);

		RadioGroup rgFilter = view.findViewById(R.id.rgFilter);
		Switch swFluid = view.findViewById(R.id.swFluid);
		Switch swJoystick = view.findViewById(R.id.swJoystick);
		Switch swShowFps = view.findViewById(R.id.swShowFps);
		Spinner spOrientation = view.findViewById(R.id.spOrientation);

		switch (settings.getFilterMode()) {
			case LibrarySettings.FILTER_OFF:
				rgFilter.check(R.id.rbFilterOff);
				break;
			case LibrarySettings.FILTER_SMOOTH:
				rgFilter.check(R.id.rbFilterSmooth);
				break;
			default:
				rgFilter.check(R.id.rbFilterHd);
				break;
		}
		swFluid.setChecked(settings.getFluidMode());
		swJoystick.setChecked(settings.getJoystick());
		swShowFps.setChecked(settings.getShowFps());

		ArrayAdapter<CharSequence> spinnerAdapter = ArrayAdapter.createFromResource(this,
				R.array.library_orientation_entries, android.R.layout.simple_spinner_item);
		spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spOrientation.setAdapter(spinnerAdapter);
		spOrientation.setSelection(settings.getOrientation());

		new AlertDialog.Builder(this)
				.setTitle(R.string.library_settings)
				.setView(view)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					int checked = rgFilter.getCheckedRadioButtonId();
					if (checked == R.id.rbFilterOff) {
						settings.setFilterMode(LibrarySettings.FILTER_OFF);
					} else if (checked == R.id.rbFilterSmooth) {
						settings.setFilterMode(LibrarySettings.FILTER_SMOOTH);
					} else {
						settings.setFilterMode(LibrarySettings.FILTER_HD);
					}
					settings.setFluidMode(swFluid.isChecked());
					settings.setJoystick(swJoystick.isChecked());
					settings.setShowFps(swShowFps.isChecked());
					settings.setOrientation(spOrientation.getSelectedItemPosition());
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private Bitmap loadIcon(String path) {
		Bitmap cached = iconCache.get(path);
		if (cached != null) {
			return cached;
		}
		try {
			Bitmap bitmap = BitmapFactory.decodeStream(getAssets().open(path));
			if (bitmap != null) {
				iconCache.put(path, bitmap);
			}
			return bitmap;
		} catch (IOException e) {
			return null;
		}
	}

	private class GameAdapter extends BaseAdapter {
		private static final int TYPE_LIST = 0;
		private static final int TYPE_GRID = 1;

		private final LayoutInflater inflater = LayoutInflater.from(LibraryActivity.this);

		@Override
		public int getCount() {
			return visibleGames.size();
		}

		@Override
		public int getViewTypeCount() {
			return 2;
		}

		@Override
		public int getItemViewType(int position) {
			return viewMode == LibrarySettings.VIEW_GRID ? TYPE_GRID : TYPE_LIST;
		}

		@Override
		public Object getItem(int position) {
			return visibleGames.get(position);
		}

		@Override
		public long getItemId(int position) {
			return position;
		}

		@NonNull
		@Override
		public View getView(int position, View convertView, @NonNull ViewGroup parent) {
			int type = getItemViewType(position);
			ViewHolder holder = null;
			if (convertView != null && convertView.getTag() instanceof ViewHolder) {
				ViewHolder candidate = (ViewHolder) convertView.getTag();
				if (candidate.type == type) {
					holder = candidate;
				}
			}
			if (holder == null) {
				convertView = inflater.inflate(
						type == TYPE_GRID ? R.layout.grid_item_game : R.layout.list_item_game,
						parent, false);
				holder = new ViewHolder(type);
				holder.icon = convertView.findViewById(R.id.ivIcon);
				holder.title = convertView.findViewById(R.id.tvGameTitle);
				holder.subtitle = convertView.findViewById(R.id.tvGameSubtitle);
				holder.star = convertView.findViewById(R.id.tvFavorite);
				convertView.setTag(holder);
			}
			GameEntry game = visibleGames.get(position);
			holder.title.setText(game.title);
			String sub = game.vendor == null ? "" : game.vendor;
			if (game.version != null && !game.version.isEmpty()) {
				sub = sub.isEmpty() ? game.version : sub + " · " + game.version;
			}
			if (holder.subtitle != null) {
				holder.subtitle.setText(sub);
			}
			if (holder.star != null) {
				holder.star.setVisibility(
						libSettings.isFavorite(game.id) ? View.VISIBLE : View.INVISIBLE);
			}

			holder.icon.setTag(game);
			holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
			if (game.icon != null) {
				final String path = game.icon;
				final ImageView target = holder.icon;
				worker.execute(() -> {
					Bitmap bitmap = loadIcon(path);
					if (bitmap != null) {
						mainHandler.post(() -> {
							if (target.getTag() == game) {
								target.setImageBitmap(bitmap);
							}
						});
					}
				});
			}
			return convertView;
		}
	}

	private static class ViewHolder {
		final int type;
		ImageView icon;
		TextView title;
		TextView subtitle;
		TextView star;

		ViewHolder(int type) {
			this.type = type;
		}
	}
}
