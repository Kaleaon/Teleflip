package com.teleflip.app.input

import android.text.Editable
import android.text.InputFilter
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class FakeEditable(private val sb: StringBuilder = StringBuilder()) : Editable, CharSequence by sb {
    override fun replace(
        st: Int,
        en: Int,
        source: CharSequence,
        start: Int,
        end: Int,
    ): Editable {
        val replacement = source.subSequence(start, end).toString()
        val safeSt = Math.max(0, Math.min(st, sb.length))
        val safeEn = Math.max(safeSt, Math.min(en, sb.length))
        sb.replace(safeSt, safeEn, replacement)
        return this
    }

    override fun replace(
        st: Int,
        en: Int,
        source: CharSequence,
    ): Editable {
        return replace(st, en, source, 0, source.length)
    }

    override fun insert(
        where: Int,
        text: CharSequence,
        start: Int,
        end: Int,
    ): Editable {
        return replace(where, where, text, start, end)
    }

    override fun insert(
        where: Int,
        text: CharSequence,
    ): Editable {
        return replace(where, where, text, 0, text.length)
    }

    override fun delete(
        st: Int,
        en: Int,
    ): Editable {
        return replace(st, en, "", 0, 0)
    }

    override fun append(text: CharSequence?): Editable {
        return replace(sb.length, sb.length, text ?: "", 0, text?.length ?: 0)
    }

    override fun append(
        text: CharSequence?,
        start: Int,
        end: Int,
    ): Editable {
        return replace(sb.length, sb.length, text ?: "", start, end)
    }

    override fun append(text: Char): Editable {
        return append(text.toString())
    }

    override fun clear() {
        sb.setLength(0)
    }

    override fun getChars(
        start: Int,
        end: Int,
        dest: CharArray,
        destoff: Int,
    ) {
        sb.getChars(start, end, dest, destoff)
    }

    override fun clearSpans() {}

    override fun setFilters(filters: Array<out InputFilter>?) {}

    override fun getFilters(): Array<InputFilter> = arrayOf()

    override fun setSpan(
        what: Any?,
        start: Int,
        end: Int,
        flags: Int,
    ) {}

    override fun removeSpan(what: Any?) {}

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getSpans(
        start: Int,
        end: Int,
        type: Class<T>?,
    ): Array<T> = java.lang.reflect.Array.newInstance(type ?: Any::class.java, 0) as Array<T>

    override fun getSpanStart(tag: Any?): Int = -1

    override fun getSpanEnd(tag: Any?): Int = -1

    override fun getSpanFlags(tag: Any?): Int = 0

    override fun nextSpanTransition(
        start: Int,
        limit: Int,
        type: Class<*>?,
    ): Int = limit

    override fun toString(): String = sb.toString()
}

class KeypadInputEngineTest {
    private lateinit var editText: EditText
    private lateinit var editable: FakeEditable
    private var selectionStart = 0
    private var selectionEnd = 0
    private var focusChangeListener: View.OnFocusChangeListener? = null
    private var attachStateChangeListener: View.OnAttachStateChangeListener? = null

    @Before
    fun setUp() {
        editText = mock(EditText::class.java)
        editable = FakeEditable()
        selectionStart = 0
        selectionEnd = 0

        `when`(editText.text).thenAnswer { editable }
        `when`(editText.selectionStart).thenAnswer { selectionStart }
        `when`(editText.selectionEnd).thenAnswer { selectionEnd }

        `when`(editText.setSelection(anyInt())).thenAnswer { invocation ->
            val pos = invocation.getArgument<Int>(0)
            selectionStart = pos
            selectionEnd = pos
            null
        }

        `when`(editText.setSelection(anyInt(), anyInt())).thenAnswer { invocation ->
            selectionStart = invocation.getArgument<Int>(0)
            selectionEnd = invocation.getArgument<Int>(1)
            null
        }

        `when`(editText.setOnFocusChangeListener(any())).thenAnswer { invocation ->
            focusChangeListener = invocation.getArgument(0)
            null
        }

        `when`(editText.addOnAttachStateChangeListener(any())).thenAnswer { invocation ->
            attachStateChangeListener = invocation.getArgument(0)
            null
        }
    }

    private fun createDownEvent(keyCode: Int): KeyEvent {
        return KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0)
    }

    private fun createUpEvent(keyCode: Int): KeyEvent {
        return KeyEvent(0L, 0L, KeyEvent.ACTION_UP, keyCode, 0)
    }

    @Test
    fun testDefaultAndCustomTimeoutMs() {
        val engineDefault = KeypadInputEngine(editText)
        assertEquals(500L, engineDefault.timeoutMs)

        val engineCustom = KeypadInputEngine(editText, timeoutMs = 300L)
        assertEquals(300L, engineCustom.timeoutMs)

        engineCustom.timeoutMs = 900L
        assertEquals(900L, engineCustom.timeoutMs)
    }

    @Test
    fun testHandleDpadRightManualCommit() {
        val engine = KeypadInputEngine(editText)
        val eventDown = createDownEvent(KeyEvent.KEYCODE_DPAD_RIGHT)
        val eventUp = createUpEvent(KeyEvent.KEYCODE_DPAD_RIGHT)

        // Press DPAD_RIGHT without active buffer
        val handledDown = engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, eventDown)
        val handledUp = engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, eventUp)

        assertTrue("DPAD_RIGHT ACTION_DOWN should be handled", handledDown)
        assertTrue("DPAD_RIGHT ACTION_UP should be handled", handledUp)
    }

    @Test
    fun testDpadRightCommitsCharacterImmediatelyInCapFirstMode() {
        val engine = KeypadInputEngine(editText, timeoutMs = 900L)

        // Press key '2' ('A' in CAP_FIRST)
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))

        // Press DPAD_RIGHT to manually commit 'A'
        val handledRight = engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, createDownEvent(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue("DPAD_RIGHT should be handled for manual commit", handledRight)

        // Next press of key '2' immediately after manual commit
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))

        assertEquals("Aa", editable.toString())
    }

    @Test
    fun testDpadRightCommitsCharacterImmediatelyInLowerMode() {
        val engine = KeypadInputEngine(editText, timeoutMs = 900L)
        engine.setMode(InputMode.LOWER)

        // Press key '2' ('a' in LOWER)
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))

        // Press DPAD_RIGHT to manually commit 'a'
        engine.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, createDownEvent(KeyEvent.KEYCODE_DPAD_RIGHT))

        // Next press of key '2' immediately after manual commit
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))

        assertEquals("aa", editable.toString())
    }

    @Test
    fun testCapitalizationCycleAtSentenceStart() {
        val engine = KeypadInputEngine(editText)

        // 1st press: 'a' -> 'A' at sentence start
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A", editable.toString())

        // 2nd press within 900ms: 'A' -> 'b'
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("b", editable.toString())

        // 3rd press: 'b' -> 'c'
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("c", editable.toString())

        // 4th press: 'c' -> '2'
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("2", editable.toString())

        // 5th press: '2' -> 'A' (wraps back to charIndex 0)
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A", editable.toString())
    }

    @Test
    fun testSentenceBoundaries() {
        val engine = KeypadInputEngine(editText)

        // 1. Start of text -> Capitalized 'A'
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A", editable.toString())

        // Commit character
        engine.commitCurrentChar()

        // 2. After space following dot: "A. "
        editable.clear()
        editable.append("A. ")
        selectionStart = 3
        selectionEnd = 3
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A. A", editable.toString())

        engine.commitCurrentChar()

        // 3. After space following question mark: "A? "
        editable.clear()
        editable.append("A? ")
        selectionStart = 3
        selectionEnd = 3
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A? A", editable.toString())

        engine.commitCurrentChar()

        // 4. After space following exclamation mark: "A! "
        editable.clear()
        editable.append("A! ")
        selectionStart = 3
        selectionEnd = 3
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A! A", editable.toString())

        engine.commitCurrentChar()

        // 5. After newline: "A\n"
        editable.clear()
        editable.append("A\n")
        selectionStart = 2
        selectionEnd = 2
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A\nA", editable.toString())

        engine.commitCurrentChar()

        // 6. Mid-sentence (after word and space): "Hello "
        editable.clear()
        editable.append("Hello ")
        selectionStart = 6
        selectionEnd = 6
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("Hello a", editable.toString())
    }

    @Test
    fun testFocusLossCommit() {
        val engine = KeypadInputEngine(editText)

        // Type key 2 once -> "A"
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A", editable.toString())

        // Simulate focus loss
        focusChangeListener?.onFocusChange(editText, false)

        // Next key 2 press should NOT cycle "A" to "b", but insert a new "a" (mid-sentence) or "A"
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("Aa", editable.toString())
    }

    @Test
    fun testViewDetachCommit() {
        val engine = KeypadInputEngine(editText)

        // Type key 2 once -> "A"
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A", editable.toString())

        // Simulate view detach
        attachStateChangeListener?.onViewDetachedFromWindow(editText)

        // Next press treated as new input sequence
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("Aa", editable.toString())
    }

    @Test
    fun testCursorSelectionChangeSafeguard() {
        val engine = KeypadInputEngine(editText)

        // Type key 2 once -> "A", selection at 1
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        assertEquals("A", editable.toString())

        // User moves cursor back to index 0
        selectionStart = 0
        selectionEnd = 0

        // Type key 3 ('d') at index 0. Since selection changed from 1 to 0, current char committed
        // and new char 'D' (sentence boundary at index 0) is inserted at index 0.
        engine.handleKeyEvent(KeyEvent.KEYCODE_3, createDownEvent(KeyEvent.KEYCODE_3))
        assertEquals("DA", editable.toString())
    }

    @Test
    fun testModeSwitchingDisabled() {
        val engine = KeypadInputEngine(editText)
        engine.setMode(InputMode.NUMERIC)
        engine.isModeSwitchingEnabled = false

        // Press #
        engine.handleKeyEvent(KeyEvent.KEYCODE_POUND, createDownEvent(KeyEvent.KEYCODE_POUND))
        assertEquals(InputMode.NUMERIC, engine.currentMode)

        // Type digits 1, 2, 3
        engine.handleKeyEvent(KeyEvent.KEYCODE_1, createDownEvent(KeyEvent.KEYCODE_1))
        engine.handleKeyEvent(KeyEvent.KEYCODE_2, createDownEvent(KeyEvent.KEYCODE_2))
        engine.handleKeyEvent(KeyEvent.KEYCODE_3, createDownEvent(KeyEvent.KEYCODE_3))
        assertEquals("123", editable.toString())
    }

    @Test
    fun testSymbolPickerDisabled() {
        var symbolRequested = false
        val engine =
            KeypadInputEngine(
                editText = editText,
                onSymbolRequested = { symbolRequested = true },
            )
        engine.isSymbolPickerEnabled = false

        engine.handleKeyEvent(KeyEvent.KEYCODE_STAR, createDownEvent(KeyEvent.KEYCODE_STAR))
        org.junit.Assert.assertFalse(symbolRequested)

        engine.isSymbolPickerEnabled = true
        engine.handleKeyEvent(KeyEvent.KEYCODE_STAR, createDownEvent(KeyEvent.KEYCODE_STAR))
        org.junit.Assert.assertTrue(symbolRequested)
    }
}
