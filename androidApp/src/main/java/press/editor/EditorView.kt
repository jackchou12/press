package press.editor

import android.content.Context
import android.text.InputType.TYPE_CLASS_TEXT
import android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
import android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
import android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
import android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
import android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
import android.view.Gravity.TOP
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.updatePaddingRelative
import com.jakewharton.rxbinding3.view.detaches
import com.squareup.contour.ContourLayout
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import io.reactivex.android.schedulers.AndroidSchedulers.mainThread
import me.saket.press.R
import me.saket.press.shared.editor.AutoCorrectEnabled
import me.saket.press.shared.editor.EditorEvent.NoteTextChanged
import me.saket.press.shared.editor.EditorOpenMode
import me.saket.press.shared.editor.EditorPresenter
import me.saket.press.shared.editor.EditorPresenter.Args
import me.saket.press.shared.editor.EditorUiEffect
import me.saket.press.shared.editor.EditorUiEffect.UpdateNoteText
import me.saket.press.shared.editor.EditorUiModel
import me.saket.press.shared.settings.Setting
import me.saket.press.shared.theme.DisplayUnits
import me.saket.press.shared.theme.EditorUiStyles
import me.saket.press.shared.theme.applyStyle
import me.saket.press.shared.theme.from
import me.saket.press.shared.ui.subscribe
import me.saket.press.shared.ui.uiUpdates
import me.saket.wysiwyg.Wysiwyg
import me.saket.wysiwyg.formatting.TextSelection
import me.saket.wysiwyg.parser.node.HeadingLevel.H1
import me.saket.wysiwyg.style.WysiwygStyle
import me.saket.wysiwyg.widgets.addTextChangedListener
import press.extensions.doOnTextChange
import press.extensions.fromOreo
import press.extensions.textColor
import press.extensions.textSizePx
import press.navigator
import press.theme.themeAware
import press.theme.themePalette
import press.theme.themed
import press.widgets.PressToolbar
import press.widgets.Truss

class EditorView @AssistedInject constructor(
  @Assisted context: Context,
  @Assisted openMode: EditorOpenMode,
  @Assisted private val onDismiss: () -> Unit,
  presenterFactory: EditorPresenter.Factory,
  autoCorrectEnabled: Setting<AutoCorrectEnabled>
) : ContourLayout(context) {

  private val toolbar = themed(PressToolbar(context)).apply {
    themeAware {
      setBackgroundColor(it.window.editorBackgroundColor)
    }
    applyLayout(
        x = leftTo { parent.left() }.rightTo { parent.right() },
        y = topTo { parent.top() }
    )
  }

  internal val scrollView = themed(ScrollView(context)).apply {
    id = R.id.editor_scrollable_container
    isFillViewport = true
    applyLayout(
        x = leftTo { parent.left() }.rightTo { parent.right() },
        y = topTo { toolbar.bottom() }.bottomTo { parent.bottom() }
    )
  }

  internal val editorEditText = themed(PlainTextPasteEditText(context)).apply {
    EditorUiStyles.editor.applyStyle(this)
    id = R.id.editor_textfield
    background = null
    breakStrategy = BREAK_STRATEGY_HIGH_QUALITY
    gravity = TOP
    inputType = TYPE_CLASS_TEXT or  // Multiline doesn't work without this.
        TYPE_TEXT_FLAG_CAP_SENTENCES or
        TYPE_TEXT_FLAG_MULTI_LINE or
        TYPE_TEXT_FLAG_NO_SUGGESTIONS
    if (autoCorrectEnabled.get()!!.enabled) {
      inputType = inputType or TYPE_TEXT_FLAG_AUTO_CORRECT
    }
    imeOptions = IME_FLAG_NO_FULLSCREEN
    movementMethod = EditorLinkMovementMethod(scrollView)
    filters += FormatMarkdownOnEnterPress(this)
    CapitalizeOnHeadingStart.capitalize(this)
    updatePaddingRelative(start = 20.dip, end = 20.dip, bottom = 20.dip)
    fromOreo {
      importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
    }
    themeAware {
      textColor = it.textColorPrimary
    }
  }

  private val headingHintTextView = themed(TextView(context)).apply {
    textSizePx = editorEditText.textSize
    themeAware {
      textColor = it.textColorHint
    }
    applyLayout(
        x = leftTo { scrollView.left() + editorEditText.paddingStart }
            .rightTo { scrollView.right() - editorEditText.paddingStart },
        y = topTo { scrollView.top() + editorEditText.paddingTop }
    )
  }

  private val presenter = presenterFactory.create(Args(
      openMode = openMode,
      archiveEmptyNoteOnExit = true,
      navigator = navigator()
  ))

  init {
    scrollView.addView(editorEditText, MATCH_PARENT, WRAP_CONTENT)
    bringChildToFront(scrollView)

    themeAware { palette ->
      setBackgroundColor(palette.window.editorBackgroundColor)
    }

    // TODO: add support for changing WysiwygStyle.
    themePalette()
        .take(1)
        .takeUntil(detaches())
        .subscribe { palette ->
          val wysiwygStyle = WysiwygStyle.from(palette.markdown, DisplayUnits(context))
          val wysiwyg = Wysiwyg(editorEditText, wysiwygStyle)
          editorEditText.addTextChangedListener(wysiwyg.syntaxHighlighter())
        }

    toolbar.setNavigationOnClickListener {
      // TODO: detect if the keyboard is up and delay going back by
      //  a bit so that the IRV behind is resized before this View
      //  start collapsing.
      onDismiss()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    editorEditText.doOnTextChange {
      presenter.dispatch(NoteTextChanged(it.toString()))
    }

    presenter.uiUpdates()
        .takeUntil(detaches())
        .observeOn(mainThread())
        .subscribe(models = ::render, effects = ::render)
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    presenter.saveEditorContentOnClose(editorEditText.text.toString())
  }

  private fun render(model: EditorUiModel) {
    if (model.hintText == null) {
      headingHintTextView.visibility = GONE
    } else {
      headingHintTextView.visibility = View.VISIBLE
      headingHintTextView.text = Truss()
          .pushSpan(EditorHeadingHintSpan(H1))
          .append(model.hintText!!)
          .popSpan()
          .build()
    }
  }

  private fun render(uiUpdate: EditorUiEffect) {
    return when (uiUpdate) {
      is UpdateNoteText -> editorEditText.setText(uiUpdate.newText, uiUpdate.newSelection)
    }
  }

  private fun EditText.setText(newText: CharSequence, newSelection: TextSelection?) {
    setText(newText)
    newSelection?.let {
      setSelection(it.start, it.end)
    }
  }

  @AssistedInject.Factory
  interface Factory {
    fun create(
      context: Context,
      openMode: EditorOpenMode,
      onDismiss: () -> Unit
    ): EditorView
  }
}
