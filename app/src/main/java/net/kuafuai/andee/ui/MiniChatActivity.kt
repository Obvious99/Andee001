package net.kuafuai.andee.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import net.kuafuai.andee.R
import net.kuafuai.andee.i18n.AppLocale
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Andee Mini: the small chat popup that a double-tap on the ball opens.
 *
 * A bottom sheet about one third of the screen tall: the last few messages, a type box with a send
 * button, ⤢ for the full card and ✕ (or a tap outside) to close.
 *
 * An Activity, not an overlay, for the same reason as [TextInputActivity]: an overlay window cannot
 * raise the soft keyboard on some ROMs. Being an Activity also means it sits *in front of* the app the
 * user is on, so it must never be there while the assistant reads or taps the screen:
 *
 *  * every screen command folds the card first (`CommandDispatcher.ensureCompact`), and that same
 *    place calls [stepAsideForTask], which closes this popup and waits for it to be gone;
 *  * if the assistant opens another app, this popup is covered and [onStop] closes it;
 *  * in both cases it remembers to come back, and [taskOver] (the task's final answer) brings it back
 *    with the reply in it. A task the user stopped does not bring it back.
 *
 * The reply is still spoken by the normal TTS; nothing about speaking or listening changes here.
 */
class MiniChatActivity : Activity() {

    private lateinit var field: EditText
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var panel: LinearLayout
    private lateinit var lctx: Context
    private var lastSeen = Long.MIN_VALUE
    private var lastSize = -1
    private var expandAsked = false
    private var keyboardAsked = false
    private val main = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            refresh(force = false)
            main.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        Theme.load(this)
        lctx = AppLocale.wrap(this)

        val third = resources.displayMetrics.heightPixels / 3

        // ---- header: title, ⤢, ✕ ----
        val title = TextView(this).apply {
            text = "Andee"
            textSize = Glass.Type.BODY
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor(Glass.TITLE))
            setPadding(dp(6), 0, 0, 0)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(glyph("⤢", "Open full screen") { expandAsked = true; close() })
            addView(glyph("✕", "Close") { close() })
        }

        // ---- the last few messages ----
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        }

        // ---- type box + send ----
        field = EditText(this).apply {
            hint = lctx.getString(R.string.input_hint)
            textSize = Glass.Type.BODY
            setTextColor(Color.parseColor(Glass.TITLE))
            setHintTextColor(Color.parseColor(Glass.SECONDARY))
            background = null
            maxLines = 3
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding(dp(10), dp(6), dp(6), dp(6))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send(); true
                } else {
                    false
                }
            }
        }
        val sendKey = TextView(this).apply {
            text = "➤"
            textSize = 20f
            contentDescription = "Send"
            setTextColor(Color.parseColor(Glass.ON_TINT))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor(Glass.ACCENT))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { send() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#22FFFFFF"))
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            minimumHeight = dp(48)
            addView(field, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(sendKey, LinearLayout.LayoutParams(dp(42), dp(42)))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true // a tap on the panel itself must not count as "outside"
            background = GradientDrawable().apply {
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor(Glass.CARD_SOLID))
            }
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(header, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(inputRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        }

        val root = FrameLayout(this).apply {
            // Light dim so the user can see where "outside" is; tapping it closes.
            setBackgroundColor(Color.parseColor("#33000000"))
            setOnClickListener { close() }
            addView(panel, FrameLayout.LayoutParams(MATCH, third, Gravity.BOTTOM))
        }
        setContentView(root)

        // Move up with the keyboard. A translucent window is not resized for the IME on every ROM
        // (see TextInputActivity), so the inset is applied by hand: the panel's bottom sits on top of
        // the keyboard (or the navigation bar), and it shrinks if one third no longer fits.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottom = maxOf(ime, bars.bottom)
            v.setPadding(0, bars.top, 0, bottom)
            val avail = (v.height - bars.top - bottom).takeIf { v.height > 0 } ?: third
            val want = third.coerceAtMost(avail.coerceAtLeast(dp(160)))
            val lp = panel.layoutParams as FrameLayout.LayoutParams
            if (lp.height != want) {
                lp.height = want
                panel.layoutParams = lp
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
            insets
        }

        // Ours, not the user's app: keep it out of the screen dump the assistant reads.
        window.decorView.hideFromAccessibility()
        OwnCard.shown()
        live = WeakReference(this)
        returnAfterTask = false
        refresh(force = true)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        refresh(force = true)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refresh(force = true)
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
        keyboardAsked = false
        field.requestFocus()
        field.post { showKeyboard() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::field.isInitialized) showKeyboard()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(poll)
        super.onPause()
    }

    /**
     * Covered by something else. If the assistant is working (it opened another app), come back when it
     * is done; otherwise the user left (Home, Recents) and the popup simply closes.
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing) {
            if (runCatching { isTaskActive?.invoke() == true }.getOrDefault(false)) returnAfterTask = true
            finish()
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        if (live?.get() === this) live = null
        resumed = false
        OwnCard.hidden()
        gone?.countDown()
        runCatching { onClosed?.invoke() }
        if (expandAsked) runCatching { onExpand?.invoke() }
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun close() {
        returnAfterTask = false
        hideKeyboard()
        finish()
    }

    private fun send() {
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        field.setText("")
        // Keyboard down so the user's app and the reply both have room; the popup itself stays.
        hideKeyboard()
        field.clearFocus()
        runCatching { onSubmit?.invoke(text) }
        refresh(force = true)
    }

    /** Redraw the last few messages when the history changed. */
    private fun refresh(force: Boolean) {
        if (!::list.isInitialized) return
        val all = ChatHistory.snapshot()
        val newestId = all.lastOrNull()?.id ?: Long.MIN_VALUE
        if (!force && newestId == lastSeen && all.size == lastSize) return
        lastSeen = newestId
        lastSize = all.size
        val rows = all.filter {
            it.role == ChatHistory.Role.USER || it.role == ChatHistory.Role.ASSISTANT || it.role == ChatHistory.Role.ERROR
        }.takeLast(SHOWN)
        list.removeAllViews()
        if (rows.isEmpty()) {
            list.addView(line("Type a message below.", Glass.SECONDARY, Gravity.START, italic = true))
        }
        rows.forEach { e ->
            when (e.role) {
                ChatHistory.Role.USER -> list.addView(bubble(e.text, mine = true))
                ChatHistory.Role.ERROR -> list.addView(line(e.text, Glass.DANGER, Gravity.START, italic = false))
                else -> list.addView(bubble(e.text, mine = false))
            }
        }
        if (rows.lastOrNull()?.role == ChatHistory.Role.USER) {
            list.addView(line("Andee is thinking…", Glass.SECONDARY, Gravity.START, italic = true))
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun bubble(text: String, mine: Boolean): View {
        val tv = TextView(this).apply {
            this.text = if (text.length > MAX_CHARS) text.take(MAX_CHARS) + "…" else text
            textSize = Glass.Type.BODY - 1f
            setTextColor(Color.parseColor(if (mine) Glass.ON_TINT else Glass.TITLE))
            setPadding(dp(10), dp(6), dp(10), dp(6))
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(if (mine) Glass.ACCENT else "#26FFFFFF"))
            }
            setTextIsSelectable(false)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (mine) Gravity.END else Gravity.START
            setPadding(if (mine) dp(40) else 0, dp(3), if (mine) 0 else dp(40), dp(3))
            addView(tv, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        return wrap
    }

    private fun line(text: String, color: String, gravity: Int, italic: Boolean): View =
        TextView(this).apply {
            this.text = text
            textSize = Glass.Type.BODY - 2f
            setTextColor(Color.parseColor(color))
            this.gravity = gravity
            if (italic) setTypeface(typeface, Typeface.ITALIC)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

    private fun glyph(label: String, desc: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 18f
            contentDescription = desc
            setTextColor(Color.parseColor(Glass.LABEL))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            isClickable = true
            isFocusable = true
            Glass.pressable(this)
            setOnClickListener { onClick() }
        }

    private fun showKeyboard() {
        if (!resumed || isFinishing || !hasWindowFocus() || keyboardAsked) return
        keyboardAsked = true
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        if (!::field.isInitialized) return
        runCatching {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken, 0)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val POLL_MS = 600L
        private const val SHOWN = 4
        private const val MAX_CHARS = 600
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val STEP_ASIDE_WAIT_MS = 900L
        private const val STEP_ASIDE_SETTLE_MS = 150L
        private const val RETURN_DELAY_MS = 500L

        /** Set once by the service. The typed sentence, as one turn. */
        @Volatile var onSubmit: ((String) -> Unit)? = null

        /** Fires from [onDestroy], whatever closed the popup. The service puts the ball back. */
        @Volatile var onClosed: (() -> Unit)? = null

        /** ⤢ — fires after [onClosed]: the service unfolds the full card. */
        @Volatile var onExpand: (() -> Unit)? = null

        /** The popup was put away for a task and the task is over: the service reopens it. */
        @Volatile var onReturn: (() -> Unit)? = null

        /** Whether the assistant is working right now. Main thread only. */
        @Volatile var isTaskActive: (() -> Boolean)? = null

        private var live: WeakReference<MiniChatActivity>? = null
        @Volatile private var resumed = false
        @Volatile private var returnAfterTask = false
        @Volatile private var gone: CountDownLatch? = null
        private val mainHandler = Handler(Looper.getMainLooper())

        /** On screen (resumed), not merely alive. */
        val isShowing: Boolean get() = resumed && live?.get() != null

        /** Alive at all, on screen or not. */
        val isAlive: Boolean get() = live?.get() != null

        fun start(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, MiniChatActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }
        }

        /** Close it if it is up (user's choice: no coming back). */
        fun dismiss() {
            mainHandler.post { live?.get()?.close() }
        }

        /**
         * The assistant is about to read or touch the screen. If the popup is up, close it, remember to
         * come back after the task, and (off the main thread) wait until it is really gone so it is not
         * in the screenshot and does not catch the taps.
         */
        fun stepAsideForTask() {
            if (live?.get() == null) return
            returnAfterTask = true
            val latch = CountDownLatch(1)
            gone = latch
            mainHandler.post {
                val act = live?.get()
                if (act == null || act.isFinishing) latch.countDown() else {
                    act.hideKeyboard()
                    act.finish()
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) return
            runCatching { latch.await(STEP_ASIDE_WAIT_MS, TimeUnit.MILLISECONDS) }
            runCatching { Thread.sleep(STEP_ASIDE_SETTLE_MS) }
            if (gone === latch) gone = null
        }

        /**
         * The task ended. [answered] = the final answer arrived (bring the popup back with it);
         * false = the user stopped it (stay closed).
         */
        fun taskOver(answered: Boolean) {
            if (!returnAfterTask) return
            returnAfterTask = false
            if (!answered) return
            mainHandler.postDelayed({
                if (!isAlive) runCatching { onReturn?.invoke() }
            }, RETURN_DELAY_MS)
        }
    }
}
