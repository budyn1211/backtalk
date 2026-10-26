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

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Filter
import com.google.android.accessibility.utils.Role
import com.google.android.accessibility.utils.traversal.TraversalStrategy
import com.google.android.accessibility.utils.traversal.TraversalStrategyUtils
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Scrolls a list that hides its scrolling from accessibility services by dragging it, as a person
 * would (#150).
 *
 * Some lists, such as the Kindle library, say that they can't scroll and refuse the scroll actions,
 * yet scroll when dragged. When a swipe passes the last item on screen in such a list, Backtalk
 * drags the list with two fingers, which touch exploration passes through to the app; a one-finger
 * drag would be touch exploration. The fingers hold still before they lift, so the list doesn't
 * fling past items that were never on screen.
 */
class DragScroller(private val service: AccessibilityService) {

  /** Hears whether a drag moved the list. Called on the main thread. */
  interface Listener {
    /** The item the swipe started from moved, or is gone, so [list] scrolled. */
    fun onScrolled(list: AccessibilityNodeInfoCompat, pivotBefore: Rect, pivotAfter: Rect?)

    /** The drag finished, but nothing moved: [list] is at its end. */
    fun onNotScrolled(list: AccessibilityNodeInfoCompat)
  }

  private val handler by lazy { Handler(Looper.getMainLooper()) }

  // The item a drag last failed to move the list from. Swiping on from it again leaves the list at
  // once, rather than dragging a list that is already at its end.
  private var lastFailedPivot: AccessibilityNodeInfoCompat? = null

  /**
   * Returns the list that refuses scroll actions that a swipe from [pivot] in [direction] could
   * drag, or null. This only looks at the pivot's ancestors, so it is cheap enough for every swipe.
   */
  fun findListToDrag(
    pivot: AccessibilityNodeInfoCompat,
    @TraversalStrategy.SearchDirection direction: Int,
  ): AccessibilityNodeInfoCompat? {
    if (isForward(direction) == null || pivot == lastFailedPivot) {
      return null
    }
    return AccessibilityNodeInfoUtils.getMatchingAncestor(pivot, Filter.node(::isUnscrollableList))
  }

  /**
   * Drags [list], from [findListToDrag], when [pivot] is its last item on screen in [direction].
   *
   * @return whether a drag started; [listener] then hears how it went, unless it was cancelled
   */
  fun dragAtEdge(
    list: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
    @TraversalStrategy.SearchDirection direction: Int,
    traversal: TraversalStrategy,
    listener: Listener,
  ): Boolean {
    val forward = isForward(direction) ?: return false
    val next =
      TraversalStrategyUtils.searchFocus(
        traversal,
        pivot,
        direction,
        AccessibilityNodeInfoUtils.FILTER_SHOULD_FOCUS,
      )
    if (next != null && AccessibilityNodeInfoUtils.hasAncestor(next, list)) {
      return false
    }

    val listBounds = Rect().also(list::getBoundsInScreen)
    val pivotBefore = Rect().also(pivot::getBoundsInScreen)
    if (!mayHaveMore(list, listBounds, pivot, pivotBefore, forward)) {
      return false
    }
    val fingers = dragFingers(listBounds, pivotBefore, forward) ?: return false
    // The fingers hold still afterwards with continueStroke, which needs these same strokes.
    val strokes =
      fingers.map { (start, end) ->
        val path =
          Path().apply {
            moveTo(start.x.toFloat(), start.y.toFloat())
            lineTo(end.x.toFloat(), end.y.toFloat())
          }
        StrokeDescription(path, 0, MOVE_MS, /* willContinue= */ true)
      }
    val move = GestureDescription.Builder().apply { strokes.forEach(::addStroke) }.build()
    val callback =
      object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) {
          hold(strokes, fingers, list, pivot, pivotBefore, listener)
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
          LogUtils.d(TAG, "The drag was cancelled")
        }
      }
    LogUtils.d(TAG, "Dragging a list that refuses scroll actions, forward=%b", forward)
    return service.dispatchGesture(move, callback, handler)
  }

  /** Keeps the fingers still for a moment, then lifts them, so the list stops where it is. */
  private fun hold(
    moving: List<StrokeDescription>,
    fingers: List<Pair<Point, Point>>,
    list: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
    pivotBefore: Rect,
    listener: Listener,
  ) {
    val hold = GestureDescription.Builder()
    moving.forEachIndexed { i, stroke ->
      val end = fingers[i].second
      val path = Path().apply { moveTo(end.x.toFloat(), end.y.toFloat()) }
      hold.addStroke(stroke.continueStroke(path, 0, HOLD_MS, /* willContinue= */ false))
    }
    val callback =
      object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription) {
          check(list, pivot, pivotBefore, listener, CHECK_RETRIES)
        }

        override fun onCancelled(gestureDescription: GestureDescription) {
          LogUtils.d(TAG, "The drag was cancelled before the fingers lifted")
        }
      }
    service.dispatchGesture(hold.build(), callback, handler)
  }

  /** Reports whether [pivot] moved, waiting a little for apps that move the list late. */
  private fun check(
    list: AccessibilityNodeInfoCompat,
    pivot: AccessibilityNodeInfoCompat,
    pivotBefore: Rect,
    listener: Listener,
    retries: Int,
  ) {
    val now = AccessibilityNodeInfoCompat.obtain(pivot)
    val pivotAfter = if (now.refresh()) Rect().also(now::getBoundsInScreen) else null
    if (pivotAfter == pivotBefore) {
      if (retries > 0) {
        handler.postDelayed({ check(list, pivot, pivotBefore, listener, retries - 1) }, CHECK_RETRY_MS)
        return
      }
      lastFailedPivot = pivot
      listener.onNotScrolled(list)
      return
    }
    lastFailedPivot = null
    listener.onScrolled(list, pivotBefore, pivotAfter)
  }

  companion object {
    private const val TAG = "DragScroller"

    /** How long the fingers move. */
    private const val MOVE_MS = 300L

    /** How long the fingers hold still before they lift, which stops a fling. */
    private const val HOLD_MS = 100L

    private const val CHECK_RETRY_MS = 50L
    private const val CHECK_RETRIES = 6

    /** Share of the list's height left at each end of a drag, so it starts inside the list. */
    private const val MARGIN = 0.1f

    /** Share of the list's width between the two fingers. */
    private const val FINGER_SPREAD = 0.2f

    /** Returns true for forward, false for backward, and null for a sideways [direction]. */
    @JvmStatic
    fun isForward(@TraversalStrategy.SearchDirection direction: Int): Boolean? =
      when (direction) {
        TraversalStrategy.SEARCH_FOCUS_FORWARD,
        TraversalStrategy.SEARCH_FOCUS_DOWN -> true
        TraversalStrategy.SEARCH_FOCUS_BACKWARD,
        TraversalStrategy.SEARCH_FOCUS_UP -> false
        else -> null
      }

    /** How close to the list's edge an item that is cut off by it can end, in pixels. */
    private const val EDGE_SLOP_PX = 8

    /**
     * Whether [list] may have more items past [pivot]. A list that fits on screen refuses scroll
     * actions too, so a drag needs a sign that there is more: the list says how many rows it has
     * and the pivot isn't in the last one, or, when it doesn't say, the pivot reaches the list's
     * edge, as an item cut off by it does.
     */
    private fun mayHaveMore(
      list: AccessibilityNodeInfoCompat,
      listBounds: Rect,
      pivot: AccessibilityNodeInfoCompat,
      pivotBounds: Rect,
      forward: Boolean,
    ): Boolean {
      val rows = list.collectionInfo?.rowCount ?: -1
      var item: AccessibilityNodeInfoCompat? = pivot
      while (item != null && item != list && item.collectionItemInfo == null) {
        item = item.parent
      }
      val info = item?.takeIf { it != list }?.collectionItemInfo
      return if (rows > 0 && info != null) {
        rowsRemain(rows, info.rowIndex, info.rowSpan, forward)
      } else {
        reachesEdge(listBounds, pivotBounds, forward)
      }
    }

    /** Whether a list of [rows] has rows past the item at [rowIndex] spanning [rowSpan]. */
    @VisibleForTesting
    @JvmStatic
    fun rowsRemain(rows: Int, rowIndex: Int, rowSpan: Int, forward: Boolean): Boolean =
      if (forward) rowIndex + maxOf(rowSpan, 1) < rows else rowIndex > 0

    /** Whether [pivot] reaches [list]'s far edge in the direction, as an item cut off by it does. */
    @VisibleForTesting
    @JvmStatic
    fun reachesEdge(list: Rect, pivot: Rect, forward: Boolean): Boolean =
      if (forward) pivot.bottom >= list.bottom - EDGE_SLOP_PX
      else pivot.top <= list.top + EDGE_SLOP_PX

    /** Whether [node] is a list or grid that doesn't accept any scroll action. */
    @JvmStatic
    fun isUnscrollableList(node: AccessibilityNodeInfoCompat): Boolean {
      val role = Role.getRole(node)
      if (role != Role.ROLE_LIST && role != Role.ROLE_GRID && node.collectionInfo == null) {
        return false
      }
      return !node.isScrollable && node.actionList.none { it.id in SCROLL_ACTIONS }
    }

    private val SCROLL_ACTIONS =
      setOf(
        AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD,
        AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD,
        android.R.id.accessibilityActionScrollUp,
        android.R.id.accessibilityActionScrollDown,
        android.R.id.accessibilityActionPageUp,
        android.R.id.accessibilityActionPageDown,
      )

    /**
     * Returns the start and end of each of two fingers that drag [list] so that [pivot] moves to
     * the far edge: to the top when going [forward], and to the bottom when going back. The drag
     * stays [MARGIN] inside the list at both ends. Returns null if the list is too small to drag.
     */
    @VisibleForTesting
    @JvmStatic
    fun dragFingers(list: Rect, pivot: Rect, forward: Boolean): List<Pair<Point, Point>>? {
      val margin = (list.height() * MARGIN).toInt()
      val longest = list.height() - 2 * margin
      // Moving the pivot to the far edge shows what came after it, without passing anything.
      val wanted = if (forward) pivot.top - list.top else list.bottom - pivot.bottom
      val distance = wanted.coerceIn(longest / 2, longest)
      if (distance <= 0 || list.width() <= 0) {
        return null
      }
      val startY = if (forward) list.bottom - margin else list.top + margin
      val endY = if (forward) startY - distance else startY + distance
      val spread = (list.width() * FINGER_SPREAD / 2).toInt()
      return listOf(list.centerX() - spread, list.centerX() + spread).map { x ->
        Point(x, startY) to Point(x, endY)
      }
    }
  }
}
