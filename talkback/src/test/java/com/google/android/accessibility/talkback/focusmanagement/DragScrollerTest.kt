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

package com.google.android.accessibility.talkback.focusmanagement

import android.graphics.Point
import android.graphics.Rect
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.CollectionInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.DragScroller.Companion.dragFingers
import com.google.android.accessibility.talkback.focusmanagement.DragScroller.Companion.isForward
import com.google.android.accessibility.talkback.focusmanagement.DragScroller.Companion.isUnscrollableList
import com.google.android.accessibility.talkback.focusmanagement.DragScroller.Companion.reachesEdge
import com.google.android.accessibility.talkback.focusmanagement.DragScroller.Companion.rowsRemain
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DragScrollerTest {
  // A list 1000 pixels high, so the margin at each end is 100.
  private val list = Rect(0, 200, 800, 1200)

  @Test
  fun forward_movesThePivotToTheTop() {
    val fingers = dragFingers(list, pivot = Rect(0, 900, 800, 1100), forward = true)!!
    // Starts 100 above the bottom, and moves up by the pivot's distance from the top.
    assertEquals(listOf(Point(320, 1100) to Point(320, 400), Point(480, 1100) to Point(480, 400)), fingers)
  }

  @Test
  fun backward_movesThePivotToTheBottom() {
    val fingers = dragFingers(list, pivot = Rect(0, 300, 800, 500), forward = false)!!
    assertEquals(Point(320, 300) to Point(320, 1000), fingers[0])
  }

  @Test
  fun aPivotNearTheFarEdge_stillDragsHalfTheList() {
    val fingers = dragFingers(list, pivot = Rect(0, 210, 800, 260), forward = true)!!
    assertEquals(400, fingers[0].first.y - fingers[0].second.y)
  }

  @Test
  fun aTallPivot_dragsNoFurtherThanTheListAllows() {
    val fingers = dragFingers(list, pivot = Rect(0, 1150, 800, 1400), forward = true)!!
    assertEquals(Point(320, 1100) to Point(320, 300), fingers[0])
  }

  @Test
  fun anEmptyList_isNotDragged() {
    assertNull(dragFingers(Rect(0, 200, 800, 200), Rect(0, 200, 800, 200), forward = true))
  }

  @Test
  fun rowsRemain_untilTheLastRow() {
    assertTrue(rowsRemain(rows = 20, rowIndex = 4, rowSpan = 1, forward = true))
    assertFalse(rowsRemain(rows = 20, rowIndex = 19, rowSpan = 1, forward = true))
    assertFalse(rowsRemain(rows = 20, rowIndex = 18, rowSpan = 2, forward = true))
    assertTrue(rowsRemain(rows = 20, rowIndex = 4, rowSpan = 1, forward = false))
    assertFalse(rowsRemain(rows = 20, rowIndex = 0, rowSpan = 1, forward = false))
  }

  @Test
  fun anItemCutOffByTheList_reachesItsEdge() {
    assertTrue(reachesEdge(list, Rect(0, 1000, 800, 1200), forward = true))
    assertTrue(reachesEdge(list, Rect(0, 1000, 800, 1195), forward = true))
    assertTrue(reachesEdge(list, Rect(0, 200, 800, 400), forward = false))
  }

  @Test
  fun anItemWithSpaceAfterIt_doesNotReachTheEdge() {
    assertFalse(reachesEdge(list, Rect(0, 800, 800, 1000), forward = true))
    assertFalse(reachesEdge(list, Rect(0, 400, 800, 600), forward = false))
  }

  @Test
  fun directions() {
    assertEquals(true, isForward(TraversalStrategy.SEARCH_FOCUS_FORWARD))
    assertEquals(true, isForward(TraversalStrategy.SEARCH_FOCUS_DOWN))
    assertEquals(false, isForward(TraversalStrategy.SEARCH_FOCUS_BACKWARD))
    assertEquals(false, isForward(TraversalStrategy.SEARCH_FOCUS_UP))
    assertNull(isForward(TraversalStrategy.SEARCH_FOCUS_LEFT))
    assertNull(isForward(TraversalStrategy.SEARCH_FOCUS_RIGHT))
  }

  @Test
  fun aCollectionWithoutScrollActions_isUnscrollable() {
    assertTrue(isUnscrollableList(collection()))
  }

  @Test
  fun aCollectionThatScrolls_isNotUnscrollable() {
    val node = collection()
    node.isScrollable = true
    node.addAction(AccessibilityActionCompat.ACTION_SCROLL_FORWARD)
    assertFalse(isUnscrollableList(node))
  }

  @Test
  fun aCollectionWithOnlyAScrollAction_isNotUnscrollable() {
    val node = collection()
    node.addAction(AccessibilityActionCompat.ACTION_SCROLL_DOWN)
    assertFalse(isUnscrollableList(node))
  }

  @Test
  fun aPlainView_isNotAList() {
    assertFalse(isUnscrollableList(AccessibilityNodeInfoCompat.obtain()))
  }

  private fun collection(): AccessibilityNodeInfoCompat =
    AccessibilityNodeInfoCompat.obtain().apply {
      setCollectionInfo(CollectionInfoCompat.obtain(5, 1, false))
    }
}
