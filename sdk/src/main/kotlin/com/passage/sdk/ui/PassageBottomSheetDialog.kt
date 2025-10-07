package com.passage.sdk.ui

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.passage.sdk.logging.PassageLogger

/**
 * Bottom Sheet Dialog for Passage SDK
 * Matches the functionality of BottomSheetViewController in the Swift SDK
 */
class PassageBottomSheetDialog : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "PassageBottomSheet"

        private const val ARG_TITLE = "title"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_POINTS = "points"
        private const val ARG_CLOSE_BUTTON_TEXT = "closeButtonText"
        private const val ARG_SHOW_INPUT = "showInput"

        fun newInstance(
            title: String?,
            description: String?,
            points: List<String>?,
            closeButtonText: String?,
            showInput: Boolean = false,
            onSubmit: ((String) -> Unit)? = null
        ): PassageBottomSheetDialog {
            return PassageBottomSheetDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putString(ARG_DESCRIPTION, description)
                    putStringArrayList(ARG_POINTS, points?.let { ArrayList(it) })
                    putString(ARG_CLOSE_BUTTON_TEXT, closeButtonText ?: if (showInput) "Submit" else null)
                    putBoolean(ARG_SHOW_INPUT, showInput)
                }
                this.onSubmitCallback = onSubmit
            }
        }
    }

    private var onSubmitCallback: ((String) -> Unit)? = null

    private lateinit var rootView: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var descriptionView: TextView
    private lateinit var bulletPointsContainer: LinearLayout
    private lateinit var inputField: EditText
    private lateinit var closeButton: Button

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        PassageLogger.info(TAG, "Creating bottom sheet view")

        rootView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(24), dpToPx(16), dpToPx(12))
        }

        val title = arguments?.getString(ARG_TITLE)
        val description = arguments?.getString(ARG_DESCRIPTION)
        val points = arguments?.getStringArrayList(ARG_POINTS)
        val closeButtonText = arguments?.getString(ARG_CLOSE_BUTTON_TEXT)
        val showInput = arguments?.getBoolean(ARG_SHOW_INPUT, false) ?: false

        PassageLogger.debug(TAG, "Bottom sheet params - title: ${title}, showInput: $showInput")

        // Title
        if (!title.isNullOrEmpty()) {
            titleView = TextView(requireContext()).apply {
                text = title
                textSize = 22f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, dpToPx(8))
            }
            rootView.addView(titleView)
        }

        // Description
        if (!description.isNullOrEmpty()) {
            descriptionView = TextView(requireContext()).apply {
                text = description
                textSize = 16f
                setPadding(0, 0, 0, if (showInput) dpToPx(20) else dpToPx(16))
            }
            rootView.addView(descriptionView)
        }

        // Bullet points
        if (!points.isNullOrEmpty()) {
            bulletPointsContainer = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, if (showInput) dpToPx(20) else 0)
            }

            for (point in points) {
                val bulletView = createBulletPointView(point)
                bulletPointsContainer.addView(bulletView)
            }

            rootView.addView(bulletPointsContainer)
        }

        // Input field
        if (showInput) {
            PassageLogger.info(TAG, "Adding input field to bottom sheet")
            inputField = EditText(requireContext()).apply {
                hint = "https://google.com"
                textSize = 16f
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
                imeOptions = EditorInfo.IME_ACTION_GO
                setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))

                // Style as rounded rect
                background = createRoundedBackground()

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(44)
                ).apply {
                    bottomMargin = dpToPx(20)
                }

                // Text change listener
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        updateButtonState()
                    }
                })

                // Handle "Go" key
                setOnEditorActionListener { _, actionId, _ ->
                    if (actionId == EditorInfo.IME_ACTION_GO) {
                        handleSubmit()
                        true
                    } else {
                        false
                    }
                }
            }
            rootView.addView(inputField)
        }

        // Close/Submit button
        if (!closeButtonText.isNullOrEmpty()) {
            closeButton = Button(requireContext()).apply {
                text = closeButtonText
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                isAllCaps = false

                // Rounded corners
                background = createRoundedButtonBackground()

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(50)
                )

                // Initially disable if input is shown
                if (showInput) {
                    isEnabled = false
                    alpha = 0.5f
                }

                setOnClickListener {
                    if (showInput) {
                        handleSubmit()
                    } else {
                        dismiss()
                    }
                }
            }
            rootView.addView(closeButton)
        }

        return rootView
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog

        // Make dialog non-dismissible by dragging
        dialog.behavior.isDraggable = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.isHideable = true

        // Adjust keyboard behavior
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        return dialog
    }

    private fun createBulletPointView(text: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(8))

            val bulletView = TextView(requireContext()).apply {
                this.text = "•"
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(20),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val textView = TextView(requireContext()).apply {
                this.text = text
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            addView(bulletView)
            addView(textView)
        }
    }

    private fun updateButtonState() {
        if (!this::closeButton.isInitialized || !this::inputField.isInitialized) return

        val url = inputField.text.toString()
        val isValid = isValidUrl(url)

        closeButton.isEnabled = isValid
        closeButton.alpha = if (isValid) 1.0f else 0.5f
    }

    private fun isValidUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun handleSubmit() {
        val url = inputField.text.toString()
        if (isValidUrl(url)) {
            PassageLogger.info(TAG, "Submit button tapped with valid URL: $url")
            onSubmitCallback?.invoke(url)
            dismiss()
        }
    }

    private fun createRoundedBackground(): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(8).toFloat()
            setColor(android.graphics.Color.parseColor("#F5F5F5"))
            setStroke(dpToPx(1), android.graphics.Color.parseColor("#DDDDDD"))
        }
        return shape
    }

    private fun createRoundedButtonBackground(): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(12).toFloat()
            setColor(android.graphics.Color.BLACK)
        }
        return shape
    }

    private fun dpToPx(dp: Int): Int {
        val density = resources.displayMetrics.density
        return (dp * density).toInt()
    }

    fun updateContent(
        title: String?,
        description: String?,
        points: List<String>?,
        closeButtonText: String?,
        showInput: Boolean = false,
        onSubmit: ((String) -> Unit)? = null
    ) {
        PassageLogger.info(TAG, "Updating bottom sheet content")

        // Update the arguments bundle (can't reassign val, must modify existing)
        arguments?.apply {
            putString(ARG_TITLE, title)
            putString(ARG_DESCRIPTION, description)
            putStringArrayList(ARG_POINTS, points?.let { ArrayList(it) })
            putString(ARG_CLOSE_BUTTON_TEXT, closeButtonText ?: if (showInput) "Submit" else null)
            putBoolean(ARG_SHOW_INPUT, showInput)
        }
        onSubmitCallback = onSubmit

        // Recreate view if already created
        if (isAdded && view != null) {
            (view?.parent as? ViewGroup)?.removeAllViews()
            val newView = onCreateView(layoutInflater, view?.parent as? ViewGroup, null)
            (view?.parent as? ViewGroup)?.addView(newView)
        }
    }
}
