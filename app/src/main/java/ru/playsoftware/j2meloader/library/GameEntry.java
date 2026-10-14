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

import java.util.List;

/** One game of the bundled collection, as described by assets/games/index.json. */
public class GameEntry {
	public String id;
	public String file;
	public String title;
	public String vendor;
	public String version;
	public String icon;
	public List<String> midlets;

	@Override
	public String toString() {
		return title;
	}
}
