package com.vibes.autosilenttimer

import android.content.Context
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import com.google.android.material.chip.Chip
import com.vibes.autosilenttimer.databinding.OverlaySilentPromptBinding

/**
 * Owns the full-screen `WindowManager` overlay shown when the user switches to
 * vibrate/silent.
 *
 * The dimmed background is provided by the window itself (FLAG_DIM_BEHIND); this
 * controller inflates a centred card offering preset durations and a custom
 * amount + unit row. Selecting a preset clears the custom field and vice-versa,
 * so exactly one source is "active" when OK is pressed.
 *
 * @param context  the service [Context] the overlay is hosted from.
 * @param onOk     invoked with the chosen duration in milliseconds (always > 0).
 * @param onCancel invoked when the user cancels (button, tap-outside, or BACK).
 */
class OverlayController(
    private val context: Context,
    private val onOk: (Long) -> Unit,
    private val onCancel: () -> Unit
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val themedContext =
        ContextThemeWrapper(context, R.style.Theme_AutoSilentTimer_Overlay)

    private var rootView: View? = null

    /** Guards the preset <-> custom mutual-clearing so it cannot recurse. */
    private var suppressCustomClear = false
    private var suppressPresetClear = false

    /** True while the overlay is currently attached to the window manager. */
    val isShowing: Boolean
        get() = rootView != null

    /** Shows the overlay. No-op if it is already showing. */
    fun show() {
        if (isShowing) return

        val binding = OverlaySilentPromptBinding.inflate(LayoutInflater.from(themedContext))

        // Top-level container intercepts the BACK key (the window is focusable so
        // it receives key events) and treats it as a cancel.
        val container = object : FrameLayout(themedContext) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                    event.action == KeyEvent.ACTION_UP
                ) {
                    onCancel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.isFocusableInTouchMode = true
        container.addView(
            binding.root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        setupPresets(binding)
        setupCustomRow(binding)
        applyLastDurationSelection(binding)
        setupButtons(binding)

        // Tap on the transparent area outside the card cancels; the card itself is
        // clickable so taps inside it do not propagate to the root.
        binding.overlayRoot.setOnClickListener { onCancel() }
        binding.card.setOnClickListener { /* swallow inner taps */ }

        rootView = container
        try {
            windowManager.addView(container, buildLayoutParams())
            container.requestFocus()
        } catch (e: Exception) {
            // e.g. missing SYSTEM_ALERT_WINDOW (BadTokenException): bail cleanly.
            rootView = null
        }
    }

    /** Removes the overlay if showing. Safe to call when already dismissed. */
    fun dismiss() {
        val view = rootView ?: return
        rootView = null
        try {
            windowManager.removeView(view)
        } catch (e: IllegalArgumentException) {
            // View was not attached; nothing to remove.
        }
    }

    private fun setupPresets(binding: OverlaySilentPromptBinding) {
        val group = binding.presetsGroup
        for (preset in PRESETS) {
            val chip = Chip(
                themedContext,
                null,
                com.google.android.material.R.attr.chipStyle
            ).apply {
                id = View.generateViewId()
                text = preset.label
                tag = preset.millis
                isCheckable = true
                isSingleLine = true
            }
            group.addView(chip)
        }
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isNotEmpty() && !suppressPresetClear) {
                // A preset became the active source: clear the custom amount.
                suppressCustomClear = true
                binding.customAmount.text?.clear()
                suppressCustomClear = false
            }
        }
    }

    private fun setupCustomRow(binding: OverlaySilentPromptBinding) {
        val units = context.resources.getStringArray(R.array.time_units)
        val adapter = ArrayAdapter(
            themedContext,
            android.R.layout.simple_spinner_item,
            units
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.customUnit.adapter = adapter

        val lastUnit = Prefs(context).lastUnit
        val selectedIndex = units.indexOfFirst { it.equals(lastUnit, ignoreCase = true) }
        binding.customUnit.setSelection(if (selectedIndex >= 0) selectedIndex else 0)

        // Typing in (or focusing) the custom field makes it the active source and
        // clears any selected preset.
        binding.customAmount.addTextChangedListener { editable ->
            if (!suppressCustomClear && !editable.isNullOrEmpty()) {
                clearPresetSelection(binding)
            }
        }
        binding.customAmount.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && !suppressCustomClear) {
                clearPresetSelection(binding)
            }
        }
    }

    private fun applyLastDurationSelection(binding: OverlaySilentPromptBinding) {
        val prefs = Prefs(context)
        when (
            val selection = selectionFromLastDuration(
                prefs.lastDurationMillis,
                prefs.lastUnit
            )
        ) {
            LastDurationSelection.None -> Unit
            is LastDurationSelection.Preset -> {
                val chip = findPresetChip(binding, selection.millis) ?: return
                binding.presetsGroup.check(chip.id)
            }
            is LastDurationSelection.Custom -> {
                setCustomUnitSelection(binding, selection.unit)
                binding.customAmount.setText(selection.value.toString())
                binding.customAmount.setSelection(binding.customAmount.text?.length ?: 0)
            }
        }
    }

    private fun setupButtons(binding: OverlaySilentPromptBinding) {
        binding.buttonCancel.setOnClickListener { onCancel() }
        binding.buttonOk.setOnClickListener { handleOk(binding) }
    }

    private fun clearPresetSelection(binding: OverlaySilentPromptBinding) {
        suppressPresetClear = true
        binding.presetsGroup.clearCheck()
        suppressPresetClear = false
    }

    private fun handleOk(binding: OverlaySilentPromptBinding) {
        val millis = computeSelectedMillis(binding)
        if (millis <= 0L) {
            Toast.makeText(context, R.string.overlay_invalid_duration, Toast.LENGTH_SHORT).show()
            return
        }
        // Remember the chosen unit for next time.
        binding.customUnit.selectedItem?.toString()?.let { label ->
            Prefs(context).lastUnit = TimeUnitOption.fromLabel(label).label
        }
        Prefs(context).lastDurationMillis = millis
        onOk(millis)
    }

    private fun computeSelectedMillis(binding: OverlaySilentPromptBinding): Long {
        val checkedId = binding.presetsGroup.checkedChipId
        if (checkedId != View.NO_ID) {
            val chip = binding.presetsGroup.findViewById<Chip?>(checkedId)
            return (chip?.tag as? Long) ?: -1L
        }
        val value = binding.customAmount.text?.toString()?.trim()?.toLongOrNull() ?: return -1L
        if (value <= 0L) return -1L
        val unitLabel = binding.customUnit.selectedItem?.toString() ?: Prefs.DEFAULT_UNIT
        return computeMillis(value, TimeUnitOption.fromLabel(unitLabel))
    }

    private fun setCustomUnitSelection(
        binding: OverlaySilentPromptBinding,
        unit: TimeUnitOption
    ) {
        val units = context.resources.getStringArray(R.array.time_units)
        val selectedIndex = units.indexOfFirst { it.equals(unit.label, ignoreCase = true) }
        binding.customUnit.setSelection(if (selectedIndex >= 0) selectedIndex else 0)
    }

    private fun findPresetChip(
        binding: OverlaySilentPromptBinding,
        millis: Long
    ): Chip? {
        val group = binding.presetsGroup
        for (index in 0 until group.childCount) {
            val chip = group.getChildAt(index) as? Chip
            if (chip?.tag == millis) return chip
        }
        return null
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no FLAG_NOT_FOCUSABLE) so the EditText can take input;
            // FLAG_DIM_BEHIND dims everything behind the window.
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.6f
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
}
