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
import android.content.Intent;
import android.content.res.AssetManager;
import android.net.Uri;
import android.util.Log;

import com.android.dx.command.dexer.Main;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.microedition.shell.MicroActivity;

import ru.playsoftware.j2meloader.util.Constants;

import ru.playsoftware.j2meloader.config.Config;
import ru.playsoftware.j2meloader.config.ProfileModel;
import ru.playsoftware.j2meloader.config.ProfilesManager;
import ru.playsoftware.j2meloader.config.ShaderInfo;
import ru.playsoftware.j2meloader.util.FileUtils;
import ru.playsoftware.j2meloader.util.ZipUtils;
import ru.woesss.j2me.jar.Descriptor;

/**
 * Prepares and launches the games bundled in the APK assets.
 *
 * The first run of every game extracts its jar from the assets and converts it
 * to dex (the MIDlet runtime of J2ME-Loader loads converted dex files). The
 * result is cached in the emulator working dir, so this cost is paid only once
 * per game.
 */
public class GameInstaller {
	private static final String TAG = GameInstaller.class.getSimpleName();

	private static final String HD_SHADER_NAME = "Suavizado HD";
	private static final String[] SHADER_FILES = {
			"suavizado_hd.ini", "suavizado_hd.fsh", "suavizado_hd.vsh"
	};

	/** Returns the working dir of the game inside the emulator dir. */
	public static File getAppDir(GameEntry game) {
		return new File(Config.getAppDir(), game.id);
	}

	/** True if the game was already extracted and converted. */
	public static boolean isPrepared(GameEntry game) {
		return new File(getAppDir(game), Config.MIDLET_DEX_FILE).isFile();
	}

	/** Extracts the jar from the APK assets and converts it to dex. */
	public static synchronized void prepare(Context context, GameEntry game) throws Exception {
		if (isPrepared(game)) {
			return;
		}
		File appDir = getAppDir(game);
		File tmpDir = new File(Config.getAppDir(), ".tmp_" + game.id);
		FileUtils.deleteDirectory(tmpDir);
		if (!tmpDir.mkdirs()) {
			throw new IOException("Can't create directory: " + tmpDir);
		}
		File resJar = new File(tmpDir, Config.MIDLET_RES_FILE);
		try {
			// 1. Extract the jar of the game from the APK assets
			try (InputStream in = context.getAssets().open(game.file, AssetManager.ACCESS_STREAMING);
				 OutputStream out = new FileOutputStream(resJar)) {
				byte[] buf = new byte[64 * 1024];
				int read;
				while ((read = in.read(buf)) > 0) {
					out.write(buf, 0, read);
				}
			}

			// 2. Convert the jar to dex, like the stock installer does
			File dexFile = new File(tmpDir, Config.MIDLET_DEX_FILE);
			try {
				Main.main(new String[]{"--no-optimize", "--core-library",
						"--output=" + dexFile.getAbsolutePath(),
						resJar.getAbsolutePath()});
			} catch (Throwable e) {
				throw new IOException("Dexing error", e);
			}
			if (!dexFile.isFile()) {
				throw new IOException("Dexing produced no output for " + game.title);
			}

			// 3. Write the manifest next to the dex
			Descriptor manifest = loadManifest(resJar);
			manifest.writeTo(new File(tmpDir, Config.MIDLET_MANIFEST_FILE));

			// 4. Extract the icon if present
			try {
				String icon = manifest.getIcon();
				if (icon != null) {
					File iconFile = new File(tmpDir, Config.MIDLET_ICON_FILE);
					ZipUtils.unzipEntry(resJar, icon, iconFile);
				}
			} catch (Throwable e) {
				Log.w(TAG, "Can't extract icon of " + game.title, e);
			}

			// 5. Move the finished app dir into place
			FileUtils.deleteDirectory(appDir);
			if (!tmpDir.renameTo(appDir)) {
				throw new IOException("Can't move '" + tmpDir + "' to '" + appDir + "'");
			}
		} finally {
			FileUtils.deleteDirectory(tmpDir);
		}
	}

	private static Descriptor loadManifest(File jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar)) {
			ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
			if (entry == null) {
				throw new IOException("JAR has no manifest");
			}
			try (InputStream is = zip.getInputStream(entry)) {
				ByteArrayOutputStream bos = new ByteArrayOutputStream(20480);
				byte[] buf = new byte[4096];
				int read;
				while ((read = is.read(buf)) != -1) {
					bos.write(buf, 0, read);
				}
				return new Descriptor(bos.toString("ISO-8859-1"), false);
			}
		}
	}

	/**
	 * Copies the bundled HD smoothing shader into the working dir, where the
	 * emulator looks for custom shaders.
	 */
	public static synchronized void ensureShaders(Context context) {
		File dir = new File(Config.getShadersDir());
		if (!dir.exists() && !dir.mkdirs()) {
			Log.w(TAG, "Can't create shaders dir: " + dir);
			return;
		}
		for (String name : SHADER_FILES) {
			File out = new File(dir, name);
			if (out.isFile()) {
				continue;
			}
			try (InputStream in = context.getAssets().open("shaders/" + name);
				 OutputStream os = new FileOutputStream(out)) {
				byte[] buf = new byte[8192];
				int read;
				while ((read = in.read(buf)) > 0) {
					os.write(buf, 0, read);
				}
			} catch (IOException e) {
				Log.w(TAG, "Can't install shader " + name, e);
			}
		}
	}

	/**
	 * Creates the configuration of the game if it does not exist yet and applies
	 * the current global library settings (filter, fluidity, orientation,
	 * joystick) on every launch, so one switch affects the whole collection.
	 */
	public static void applySettings(Context context, GameEntry game) {
		ensureShaders(context);
		File configDir = new File(Config.getConfigsDir(), game.id);
		if (!configDir.exists() && !configDir.mkdirs()) {
			Log.w(TAG, "Can't create config dir: " + configDir);
		}
		ProfileModel params = ProfilesManager.loadConfig(configDir);
		if (params == null) {
			params = new ProfileModel(configDir);
		}
		params.dir = configDir;

		LibrarySettings settings = new LibrarySettings(context);
		switch (settings.getFilterMode()) {
			case LibrarySettings.FILTER_OFF:
				params.screenFilter = false;
				params.shader = null;
				break;
			case LibrarySettings.FILTER_SMOOTH:
				params.screenFilter = true;
				params.shader = null;
				break;
			case LibrarySettings.FILTER_HD:
			default:
				params.screenFilter = true;
				params.shader = createHdShader();
				break;
		}
		params.graphicsMode = 1;
		if (settings.getFluidMode()) {
			params.fpsLimit = 0;
			params.immediateMode = true;
			params.parallelRedrawScreen = true;
		} else {
			params.fpsLimit = 60;
			params.immediateMode = false;
			params.parallelRedrawScreen = false;
		}
		params.showFps = settings.getShowFps();
		switch (settings.getOrientation()) {
			case 0:
				params.orientation = 1; // auto
				break;
			case 2:
				params.orientation = 3; // landscape
				break;
			case 1:
			default:
				params.orientation = 2; // portrait
				break;
		}
		params.showKeyboard = true;
		params.joystickMode = settings.getJoystick();

		// Dark backdrop and high-contrast overlay colors for the joystick/buttons
		params.screenBackgroundColor = 0x181818;
		params.vkBgColor = 0x2E2E2E;
		params.vkBgColorSelected = 0x0A57C8;
		params.vkFgColor = 0xF2F2F2;
		params.vkFgColorSelected = 0xFFFFFF;
		params.vkOutlineColor = 0x9A9A9A;
		params.vkAlpha = 150;

		ProfilesManager.saveConfig(params);
	}

	private static ShaderInfo createHdShader() {
		ShaderInfo shader = new ShaderInfo(HD_SHADER_NAME, "Java Games Hub");
		shader.fragment = "suavizado_hd.fsh";
		shader.vertex = "suavizado_hd.vsh";
		shader.values = new float[]{0.6f, 0f, 0f, 0f};
		return shader;
	}

	/** Applies the settings and starts the game. */
	public static void launch(Context context, GameEntry game) throws IOException {
		applySettings(context, game);
		if (!isPrepared(game)) {
			throw new IOException("Game is not prepared: " + game.title);
		}
		String path = getAppDir(game).getAbsolutePath();
		Intent intent = new Intent(Intent.ACTION_DEFAULT, Uri.parse(path),
				context, MicroActivity.class);
		intent.putExtra(Constants.KEY_MIDLET_NAME, game.title);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		context.startActivity(intent);
	}
}
