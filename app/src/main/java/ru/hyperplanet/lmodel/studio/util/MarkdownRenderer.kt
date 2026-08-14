package ru.hyperplanet.lmodel.studio.util

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan

/**
 * Рендер Markdown в Spannable: **жирный**, *курсив*, `код`, # заголовки, списки.
 * В UI пользователь видит оформление, а не сырые * и **.
 */
object MarkdownRenderer {

    fun render(source: String): CharSequence {
        if (source.isBlank()) return source
        val lines = source.replace("\r\n", "\n").split("\n")
        val out = SpannableStringBuilder()
        for ((i, rawLine) in lines.withIndex()) {
            if (i > 0) out.append("\n")
            appendLine(out, rawLine)
        }
        return out
    }

    private fun appendLine(out: SpannableStringBuilder, line: String) {
        val header = Regex("""^(#{1,3})\s+(.*)$""").find(line)
        if (header != null) {
            val level = header.groupValues[1].length
            val start = out.length
            appendInline(out, header.groupValues[2])
            val end = out.length
            val scale = when (level) {
                1 -> 1.35f
                2 -> 1.22f
                else -> 1.12f
            }
            out.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        val bullet = Regex("""^(\s*)([-*+]|\d+\.)\s+(.*)$""").find(line)
        if (bullet != null) {
            val start = out.length
            out.append("• ")
            appendInline(out, bullet.groupValues[3])
            out.setSpan(BulletSpan(12), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        appendInline(out, line)
    }

    private fun appendInline(out: SpannableStringBuilder, text: String) {
        // order: code, bold, italic
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("```", i) -> {
                    val end = text.indexOf("```", i + 3).let { if (it < 0) text.length else it }
                    val content = text.substring(i + 3, end)
                    val s = out.length
                    out.append(content)
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = if (end < text.length) end + 3 else text.length
                }
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1).let { if (it < 0) text.length else it }
                    val content = text.substring(i + 1, end)
                    val s = out.length
                    out.append(content)
                    out.setSpan(TypefaceSpan("monospace"), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = if (end < text.length) end + 1 else text.length
                }
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val marker = text.substring(i, i + 2)
                    val end = text.indexOf(marker, i + 2).let { if (it < 0) -1 else it }
                    if (end < 0) {
                        out.append(text[i]); i++
                    } else {
                        val content = text.substring(i + 2, end)
                        val s = out.length
                        out.append(content)
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 2
                    }
                }
                text.startsWith("*", i) || text.startsWith("_", i) -> {
                    val marker = text[i]
                    // avoid bold already handled
                    if (i + 1 < text.length && text[i + 1] == marker) {
                        out.append(text[i]); i++
                        continue
                    }
                    val end = text.indexOf(marker, i + 1).let { if (it < 0) -1 else it }
                    if (end < 0) {
                        out.append(text[i]); i++
                    } else {
                        val content = text.substring(i + 1, end)
                        val s = out.length
                        out.append(content)
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        i = end + 1
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
    }
}
