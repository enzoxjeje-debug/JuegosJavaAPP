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

package javax.microedition.lcdui.keyboard;

import static javax.microedition.lcdui.keyboard.KeyMapper.SE_KEY_SPECIAL_GAMING_A;
import static javax.microedition.lcdui.keyboard.KeyMapper.SE_KEY_SPECIAL_GAMING_B;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.graphics.CanvasWrapper;
import javax.microedition.shell.MicroActivity;
import javax.microedition.util.ContextHolder;

import ru.playsoftware.j2meloader.config.ProfileModel;

/**
 * Gamepad-style input overlay: an analog joystick on the left and action
 * buttons on the right, instead of the classic phone keypad.
 *
 * The joystick is floating: touching anywhere on the left half of the screen
 * grabs it, and the base is drawn at the fixed resting position while idle.
 * Deflection is translated to the MIDP arrow keys (including diagonals, which
 * press two keys at once), so every J2ME game that supports game actions can be
 * played without a numeric keypad.
 */
public class JoystickKeyboard extends VirtualKeyboard {

	private static final long FIRST_REPEAT_DELAY = 400;
	private static final long REPEAT_INTERVAL = 120;
	private static final float DEAD_ZONE = 0.22f;
	private static final float DIRECTION_THRESHOLD = 0.38f;

	private final ArrayList<PadButton> buttons = new ArrayList<>();
	private final Set<Integer> pressedKeys = new LinkedHashSet<>();

	private RectF screen = new RectF();
	private float unit;
	private float stickRadius;
	private float stickBaseX;
	private float stickBaseY;
	private float stickX;
	private float stickY;
	private int stickPointer = -1;
	private boolean stickActive;

	private final Runnable repeatTick = new Runnable() {
		@Override
		public void run() {
			Canvas canvas = target;
			if (canvas == null || pressedKeys.isEmpty()) {
				return;
			}
			for (int keyCode : pressedKeys) {
				canvas.postKeyRepeated(keyCode);
			}
			handler.postDelayed(this, REPEAT_INTERVAL);
		}
	};

	public JoystickKeyboard(ProfileModel settings) {
		super(settings);
		buttons.add(new PadButton(Canvas.KEY_FIRE, "OK"));
		buttons.add(new PadButton(SE_KEY_SPECIAL_GAMING_A, "A"));
		buttons.add(new PadButton(SE_KEY_SPECIAL_GAMING_B, "B"));
		buttons.add(new PadButton(Canvas.KEY_STAR, "*"));
		buttons.add(new PadButton(Canvas.KEY_NUM0, "0"));
		buttons.add(new PadButton(Canvas.KEY_POUND, "#"));
		buttons.add(new PadButton(Canvas.KEY_SOFT_LEFT, "L"));
		buttons.add(new PadButton(Canvas.KEY_SOFT_RIGHT, "R"));
		buttons.add(new PadButton(KeyMapper.KEY_OPTIONS_MENU, "M"));
	}

	@Override
	public void resize(RectF screen, float left, float top, float right, float bottom) {
		this.screen = new RectF(screen);
		float w = screen.width();
		float h = screen.height();
		unit = Math.min(w, h) / 10.0f;

		stickRadius = unit * 1.9f;
		stickBaseX = unit * 2.3f;
		stickBaseY = h - unit * 2.4f;
		if (!stickActive) {
			stickX = stickBaseX;
			stickY = stickBaseY;
		}

		float col1 = w - unit * 4.4f;
		float col2 = w - unit * 2.2f;
		float row1 = h - unit * 1.6f;
		float row2 = h - unit * 3.6f;
		float row3 = h - unit * 5.6f;

		// left column: * / A / #   right column: OK / 0 / B
		layoutButton(Canvas.KEY_FIRE, col2, row1, unit * 1.25f);
		layoutButton(Canvas.KEY_STAR, col1, row1, unit * 0.7f);
		layoutButton(Canvas.KEY_NUM0, col2, row2, unit * 0.7f);
		layoutButton(SE_KEY_SPECIAL_GAMING_A, col1, row2, unit * 0.85f);
		layoutButton(Canvas.KEY_POUND, col1, row3, unit * 0.7f);
		layoutButton(SE_KEY_SPECIAL_GAMING_B, col2, row3, unit * 0.85f);

		// soft keys in the top corners
		layoutRect(Canvas.KEY_SOFT_LEFT,
				unit * 0.15f, unit * 0.15f, unit * 2.75f, unit * 1.25f);
		layoutRect(Canvas.KEY_SOFT_RIGHT,
				w - unit * 2.75f, unit * 0.15f, w - unit * 0.15f, unit * 1.25f);
		// menu key under the right soft key
		layoutButton(KeyMapper.KEY_OPTIONS_MENU, w - unit * 3.9f, unit * 1.3f, unit * 0.7f);

		if (overlayView != null) {
			overlayView.postInvalidate();
		}
	}

	private void layoutButton(int keyCode, float cx, float cy, float r) {
		PadButton button = findButton(keyCode);
		if (button != null) {
			button.rect.set(cx - r, cy - r, cx + r, cy + r);
		}
	}

	private void layoutRect(int keyCode, float l, float t, float r, float b) {
		PadButton button = findButton(keyCode);
		if (button != null) {
			button.rect.set(l, t, r, b);
		}
	}

	private PadButton findButton(int keyCode) {
		for (PadButton button : buttons) {
			if (button.keyCode == keyCode) {
				return button;
			}
		}
		return null;
	}

	@Override
	public void paint(CanvasWrapper g) {
		int alpha = Math.max(settings.vkAlpha, 0x70) << 24;
		int bg = alpha | (settings.vkBgColor & 0xFFFFFF);
		int bgSelected = alpha | (settings.vkBgColorSelected & 0xFFFFFF);
		int fg = alpha | (settings.vkFgColor & 0xFFFFFF);
		int outline = alpha | (settings.vkOutlineColor & 0xFFFFFF);

		// joystick base
		RectF base = new RectF(stickBaseX - stickRadius, stickBaseY - stickRadius,
				stickBaseX + stickRadius, stickBaseY + stickRadius);
		g.setFillColor(bg);
		g.fillArc(base, 0, 360);
		g.setDrawColor(outline);
		g.drawArc(base, 0, 360);

		// knob
		float knobRadius = stickRadius * 0.42f;
		float kx = stickActive ? stickX : stickBaseX;
		float ky = stickActive ? stickY : stickBaseY;
		float dx = kx - stickBaseX;
		float dy = ky - stickBaseY;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		float maxShift = stickRadius - knobRadius;
		if (len > maxShift) {
			kx = stickBaseX + dx / len * maxShift;
			ky = stickBaseY + dy / len * maxShift;
		}
		RectF knob = new RectF(kx - knobRadius, ky - knobRadius, kx + knobRadius, ky + knobRadius);
		g.setFillColor(stickActive ? bgSelected : bg);
		g.fillArc(knob, 0, 360);
		g.setDrawColor(outline);
		g.drawArc(knob, 0, 360);

		// buttons
		for (PadButton button : buttons) {
			int corners = (int) (Math.min(button.rect.width(), button.rect.height()) * 0.3f);
			g.setFillColor(button.pressed ? bgSelected : bg);
			g.fillRoundRect(button.rect, corners, corners);
			g.setDrawColor(outline);
			g.drawRoundRect(button.rect, corners, corners);
			g.setTextColor(button.pressed ? (alpha | (settings.vkFgColorSelected & 0xFFFFFF)) : fg);
			float textScale = button.rect.width() / (unit * 1.6f);
			g.setTextScale(Math.max(0.8f, textScale));
			g.drawString(button.label, button.rect.centerX(), button.rect.centerY());
		}
		g.setTextScale(1.0f);
	}

	@Override
	public boolean pointerPressed(int pointer, float x, float y) {
		for (PadButton button : buttons) {
			if (button.rect.contains(x, y)) {
				if (button.pointerId == -1) {
					button.pointerId = pointer;
					pressButton(button);
					return true;
				}
				return false;
			}
		}
		if (stickPointer == -1 && x < screen.width() * 0.55f) {
			stickPointer = pointer;
			stickActive = true;
			stickBaseX = x;
			stickBaseY = y;
			stickX = x;
			stickY = y;
			updateStick();
			vibrate();
			invalidate();
			return true;
		}
		return false;
	}

	@Override
	public boolean pointerDragged(int pointer, float x, float y) {
		if (pointer == stickPointer) {
			stickX = x;
			stickY = y;
			updateStick();
			invalidate();
			return true;
		}
		for (PadButton button : buttons) {
			if (button.pointerId == pointer) {
				if (!button.rect.contains(x, y)) {
					button.pointerId = -1;
					releaseButton(button);
				}
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean pointerReleased(int pointer, float x, float y) {
		if (pointer == stickPointer) {
			stickPointer = -1;
			stickActive = false;
			stickX = stickBaseX;
			stickY = stickBaseY;
			updateStick();
			invalidate();
			return true;
		}
		for (PadButton button : buttons) {
			if (button.pointerId == pointer) {
				button.pointerId = -1;
				releaseButton(button);
				return true;
			}
		}
		return false;
	}

	private void pressButton(PadButton button) {
		vibrate();
		button.pressed = true;
		if (button.isMenu) {
			handler.postDelayed(button.longPressTask, 500);
		} else {
			pressKey(button.keyCode);
		}
		invalidate();
	}

	private void releaseButton(PadButton button) {
		if (!button.pressed) {
			return;
		}
		button.pressed = false;
		if (button.isMenu) {
			handler.removeCallbacks(button.longPressTask);
			if (!button.longPressed) {
				MicroActivity activity = ContextHolder.getActivity();
				if (activity != null) {
					activity.runOnUiThread(activity::openOptionsMenu);
				}
			}
			button.longPressed = false;
		} else {
			releaseKey(button.keyCode);
		}
		invalidate();
	}

	private void pressKey(int keyCode) {
		Canvas canvas = target;
		if (canvas == null) {
			return;
		}
		boolean wasEmpty = pressedKeys.isEmpty();
		if (pressedKeys.add(keyCode)) {
			canvas.postKeyPressed(keyCode);
		}
		if (wasEmpty) {
			handler.postDelayed(repeatTick, FIRST_REPEAT_DELAY);
		}
	}

	private void releaseKey(int keyCode) {
		Canvas canvas = target;
		if (canvas == null) {
			return;
		}
		if (pressedKeys.remove(keyCode)) {
			canvas.postKeyReleased(keyCode);
		}
		if (pressedKeys.isEmpty()) {
			handler.removeCallbacks(repeatTick);
		}
	}

	/** Recomputes the direction keys from the current stick deflection. */
	private void updateStick() {
		float dx = stickX - stickBaseX;
		float dy = stickY - stickBaseY;
		float len = (float) Math.sqrt(dx * dx + dy * dy);
		Set<Integer> directions = new LinkedHashSet<>();
		if (stickActive && len > stickRadius * DEAD_ZONE) {
			float nx = dx / len;
			float ny = dy / len;
			if (ny < -DIRECTION_THRESHOLD) directions.add(Canvas.KEY_UP);
			if (ny > DIRECTION_THRESHOLD) directions.add(Canvas.KEY_DOWN);
			if (nx < -DIRECTION_THRESHOLD) directions.add(Canvas.KEY_LEFT);
			if (nx > DIRECTION_THRESHOLD) directions.add(Canvas.KEY_RIGHT);
		}
		for (int key : new int[]{Canvas.KEY_UP, Canvas.KEY_DOWN, Canvas.KEY_LEFT, Canvas.KEY_RIGHT}) {
			if (directions.contains(key)) {
				if (!pressedKeys.contains(key)) {
					pressKey(key);
					vibrate();
				}
			} else if (pressedKeys.contains(key)) {
				releaseKey(key);
			}
		}
	}

	private void invalidate() {
		if (overlayView != null) {
			overlayView.postInvalidate();
		}
	}

	// The joystick is always visible and has no phone keypad layout.

	@Override
	public void show() {
	}

	@Override
	public void hide() {
	}

	@Override
	public void run() {
	}

	@Override
	public void cancel() {
		stickPointer = -1;
		stickActive = false;
		for (PadButton button : buttons) {
			button.pointerId = -1;
			if (button.pressed) {
				button.pressed = false;
				if (!button.isMenu) {
					releaseKey(button.keyCode);
				} else {
					handler.removeCallbacks(button.longPressTask);
				}
			}
			button.longPressed = false;
		}
		for (int key : new int[]{Canvas.KEY_UP, Canvas.KEY_DOWN, Canvas.KEY_LEFT, Canvas.KEY_RIGHT}) {
			if (pressedKeys.contains(key)) {
				releaseKey(key);
			}
		}
		handler.removeCallbacks(repeatTick);
		invalidate();
	}

	@Override
	public boolean isPhone() {
		return false;
	}

	@Override
	public int getLayoutEditMode() {
		return LAYOUT_EOF;
	}

	@Override
	public void setLayoutEditMode(int mode) {
	}

	@Override
	public int getLayout() {
		return TYPE_CUSTOM;
	}

	@Override
	public void setLayout(int variant) {
	}

	@Override
	public void saveScreenParams() {
	}

	private class PadButton {
		final int keyCode;
		final String label;
		final boolean isMenu;
		final RectF rect = new RectF();
		final Runnable longPressTask;
		boolean pressed;
		boolean longPressed;
		int pointerId = -1;

		PadButton(int keyCode, String label) {
			this.keyCode = keyCode;
			this.label = label;
			this.isMenu = keyCode == KeyMapper.KEY_OPTIONS_MENU;
			this.longPressTask = () -> {
				longPressed = true;
				pressed = false;
				MicroActivity activity = ContextHolder.getActivity();
				if (activity != null) {
					activity.runOnUiThread(activity::showExitConfirmation);
				}
			};
		}
	}
}
