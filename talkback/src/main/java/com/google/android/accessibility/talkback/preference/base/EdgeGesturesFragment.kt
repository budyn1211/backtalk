/*
 * Copyright 2026 Backtalk contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.preference.base

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.gesture.Edge
import com.google.android.accessibility.talkback.gesture.EdgeGestureSettings
import com.google.android.accessibility.talkback.gesture.GestureShortcutMapping
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity.TalkBackSubSettings
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_ARGS
import com.google.android.accessibility.utils.preference.PreferencesActivity.FRAGMENT_NAME

/**
 * The actions of one edge: its swipes up and down, its triple tap, and the items of its hidden
 * menu. Menu items without an action are left out of the menu, so the menu has as many items as
 * have an action, in this order.
 */
abstract class EdgeGesturesFragment(xmlResId: Int, private val edge: Edge) :
  TalkbackBaseFragment(xmlResId) {

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)
    for (position in 1..EdgeGestureSettings.MAX_MENU_ITEMS) {
      findPreference<Preference>(EdgeGestureSettings.menuItemKey(edge, position))?.title =
        getString(R.string.template_pref_edge_menu_item, position)
    }
  }

  override fun onResume() {
    super.onResume()
    // The action of a gesture is chosen on another screen, so its summary may be out of date.
    val context = requireContext()
    val prefs = SharedPreferencesUtils.getSharedPreferences(context)
    val screen = preferenceScreen ?: return
    for (index in 0 until screen.preferenceCount) {
      val category = screen.getPreference(index) as? PreferenceGroup ?: continue
      for (child in 0 until category.preferenceCount) {
        val preference = category.getPreference(child) as? GestureListPreference ?: continue
        preference.summary =
          GestureShortcutMapping.getActionString(
            context,
            prefs.getString(preference.key, preference.defaultValue),
          )
      }
    }
  }

  override fun onDisplayPreferenceDialog(preference: Preference) {
    if (preference !is GestureListPreference) {
      super.onDisplayPreferenceDialog(preference)
      return
    }
    // Null on phones and watches, which list the actions on a screen of their own.
    val fragment: Fragment? = preference.createDialogFragment()
    if (fragment is PreferenceDialogFragmentCompat) {
      @Suppress("DEPRECATION") fragment.setTargetFragment(this, 0)
      fragment.show(parentFragmentManager, preference.key)
    } else {
      // As in gesture settings, the actions are listed on a screen of their own.
      startActivity(
        Intent(context, TalkBackSubSettings::class.java)
          .putExtra(FRAGMENT_NAME, GesturePreferenceFragmentCompat::class.java.canonicalName)
          .putExtra(
            FRAGMENT_ARGS,
            GesturePreferenceFragmentCompat.createBundleForFragmentArguments(preference),
          )
      )
    }
  }
}

class LeftEdgeGesturesFragment :
  EdgeGesturesFragment(R.xml.edge_gestures_left_preferences, Edge.LEFT) {
  public override fun getTitle(): CharSequence = getText(R.string.title_pref_edge_left)
}

class RightEdgeGesturesFragment :
  EdgeGesturesFragment(R.xml.edge_gestures_right_preferences, Edge.RIGHT) {
  public override fun getTitle(): CharSequence = getText(R.string.title_pref_edge_right)
}
