// Copyright 2026 Damien Dye
// SPDX-License-Identifier: Apache-2.0

package io.autonav.alerts.bench

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.libraries.navigation.layoutcustomization.ActiveGuidanceUiState
import com.google.android.libraries.navigation.layoutcustomization.NavigationLayoutDelegate
import com.google.android.libraries.navigation.layoutcustomization.NavigationReadyUiState
import com.google.android.libraries.navigation.layoutcustomization.NavigationUiParent
import com.google.android.libraries.navigation.layoutcustomization.UiState

/**
 * Debug builds only, a bench experiment (owner, 2026-11-01: "try the layout delegate viewport on the bench";
 * DESIGN.md §8 "Padding and the zoom lock are sent only when they change"). Google's navigation sample frames the
 * car with the SDK's invisible viewport view through a `NavigationLayoutDelegate` instead of camera padding.
 * `NavigationViewForAuto` has no `setLayoutDelegate`, but the phone `NavigationView` inside it has; the bench
 * hook `--es viewport "l,t,r,b"` sets this delegate there to see whether Android Auto frames the car by it.
 *
 * The layout it gives the SDK holds only the viewport (inset by [margins], pixels) and Google's logo (which must
 * stay visible); the SDK's own cards are left out, as hard rule 2 wants them gone on the car. Our map surface
 * hides the SDK's non-map children when guidance starts or stops (§8 step 6), so the layout is shown again
 * after those passes. Main thread (the SDK calls the delegate there).
 */
class ViewportExperiment(private val margins: IntArray) : NavigationLayoutDelegate() {
    private val main = Handler(Looper.getMainLooper())
    private var layout: FrameLayout? = null

    private fun install(parent: NavigationUiParent, state: UiState, phase: String) {
        val f = FrameLayout(parent.viewContext)
        state.viewport.detach()
        state.googleLogo.detach()
        f.addView(state.viewport, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            setMargins(margins[0], margins[1], margins[2], margins[3])
        })
        f.addView(state.googleLogo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, 16, 16)
        })
        layout?.let { parent.removeNavigationLayout(it) }
        parent.setNavigationLayout(f)
        layout = f
        Log.i(TAG, "Viewport $phase margins=${margins.joinToString(",")} wide=${state.isWideMode}")
        // Our map surface hides the SDK's non-map children at guidance changes and 2 s later (§8 step 6).
        for (ms in longArrayOf(500, 2_500, 5_000)) main.postDelayed({ if (layout === f) f.visibility = View.VISIBLE }, ms)
        f.post { Log.i(TAG, "Viewport $phase laid out at ${state.viewport.left},${state.viewport.top}-${state.viewport.right},${state.viewport.bottom}") }
    }

    private fun remove(parent: NavigationUiParent, phase: String) {
        layout?.let { parent.removeNavigationLayout(it) }
        layout = null
        Log.i(TAG, "Viewport left $phase")
    }

    override fun onEnterNavigationReady(parent: NavigationUiParent, state: NavigationReadyUiState) = install(parent, state, "ready")
    override fun onLeaveNavigationReady(parent: NavigationUiParent, state: NavigationReadyUiState) = remove(parent, "ready")
    override fun onEnterActiveGuidance(parent: NavigationUiParent, ready: NavigationReadyUiState, guidance: ActiveGuidanceUiState) = install(parent, guidance, "guidance")
    override fun onLeaveActiveGuidance(parent: NavigationUiParent, guidance: ActiveGuidanceUiState, ready: NavigationReadyUiState) = remove(parent, "guidance")

    private fun View.detach() { (parent as? ViewGroup)?.removeView(this) }

    private companion object { const val TAG = "Bench" }
}
