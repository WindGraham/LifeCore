package art.windgraham.lifecore

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.TypedValue
import android.widget.TextView

/** 轻量 markdown → Spanned：标题/加粗/斜体/行内代码/围栏代码块/列表/引用/链接/表格。
 *  模型输出的常见构造全覆盖，零第三方依赖。 */
object Md {
    private val INLINE = Regex(
        """(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\*[^*\n]+\*)|(__[^_\n]+__)|(\[[^\]\n]+\]\([^)\n]+\))"""
    )

    private fun attrColor(ctx: Context, attr: Int): Int {
        val tv = TypedValue()
        ctx.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    fun render(ctx: Context, src: String): SpannableStringBuilder {
        val onSurface = attrColor(ctx, com.google.android.material.R.attr.colorOnSurface)
        val onVar = attrColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val primary = attrColor(ctx, com.google.android.material.R.attr.colorPrimary)
        val surfaceVar = attrColor(ctx, com.google.android.material.R.attr.colorSurfaceVariant)
        val outline = attrColor(ctx, com.google.android.material.R.attr.colorOutlineVariant)

        val out = SpannableStringBuilder()
        val lines = src.replace("\r\n", "\n").trim().split("\n")
        var i = 0
        val codeBuf = StringBuilder()

        fun flushCode() {
            if (codeBuf.isEmpty()) return
            val start = out.length
            out.append(codeBuf.toString().trimEnd('\n'))
            out.setSpan(StyleSpan(Typeface.MONOSPACE.style), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(RelativeSizeSpan(0.88f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(onSurface), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(BackgroundColorSpan(surfaceVar), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n")
            codeBuf.clear()
        }

        /** 行内语法：**粗** *斜* `码` [链](url) */
        fun inline(text: String) {
            var cursor = 0
            for (m in INLINE.findAll(text)) {
                if (m.range.first > cursor) out.append(text.substring(cursor, m.range.first))
                val token = m.value
                val s = out.length
                when {
                    token.startsWith("`") -> {
                        out.append(token.trim('`'))
                        out.setSpan(StyleSpan(Typeface.MONOSPACE.style), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(RelativeSizeSpan(0.9f), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(ForegroundColorSpan(primary), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        out.setSpan(BackgroundColorSpan(surfaceVar), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    token.startsWith("**") || token.startsWith("__") -> {
                        out.append(token.removePrefix("**").removeSuffix("**").removePrefix("__").removeSuffix("__"))
                        out.setSpan(StyleSpan(Typeface.BOLD), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    token.startsWith("*") -> {
                        out.append(token.trim('*'))
                        out.setSpan(StyleSpan(Typeface.ITALIC), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    token.startsWith("[") -> {
                        val link = Regex("""\[([^\]]+)\]\(([^)]+)\)""").find(token)
                        if (link != null) {
                            out.append(link.groupValues[1])
                            out.setSpan(URLSpan(link.groupValues[2]), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            out.setSpan(ForegroundColorSpan(primary), s, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        } else out.append(token)
                    }
                    else -> out.append(token)
                }
                cursor = m.range.last + 1
            }
            if (cursor < text.length) out.append(text.substring(cursor))
        }

        fun heading(text: String, scale: Float, colored: Boolean) {
            out.append("\n")
            val s0 = out.length
            inline(text)
            out.setSpan(RelativeSizeSpan(scale), s0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(StyleSpan(Typeface.BOLD), s0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (colored) out.setSpan(ForegroundColorSpan(primary), s0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            out.append("\n")
        }

        while (i < lines.size) {
            val line = lines[i]
            val t = line.trim()
            when {
                t.startsWith("```") -> {
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        codeBuf.append(lines[i]).append('\n'); i++
                    }
                    flushCode()
                }
                t.startsWith("|") -> {
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i].trim()
                        if (!row.matches(Regex("""^\|[\s\-|:]+\|$"""))) {
                            codeBuf.append(row.replace(Regex("""\s*\|\s*"""), " | ")).append('\n')
                        }
                        i++
                    }
                    flushCode()
                    continue
                }
                t.startsWith("####") -> heading(t.removePrefix("####").trim(), 0.95f, false)
                t.startsWith("###") -> heading(t.removePrefix("###").trim(), 1.05f, false)
                t.startsWith("##") -> heading(t.removePrefix("##").trim(), 1.12f, true)
                t.startsWith("#") -> heading(t.removePrefix("#").trim(), 1.22f, true)
                t.startsWith(">") -> {
                    val s0 = out.length
                    inline("│ " + t.removePrefix(">").trim())
                    out.setSpan(ForegroundColorSpan(onVar), s0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.setSpan(StyleSpan(Typeface.ITALIC), s0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append("\n")
                }
                t.matches(Regex("""^[-*]\s+.*""")) -> {
                    out.append("•  ")
                    inline(t.replaceFirst(Regex("""^[-*]\s+"""), ""))
                    out.append("\n")
                }
                t.matches(Regex("""^\d+[.、]\s+.*""")) -> {
                    val num = Regex("""^(\d+)[.、]\s*""").find(t)!!.groupValues[1]
                    out.append(num).append(".  ")
                    inline(t.replaceFirst(Regex("""^\d+[.、]\s*"""), ""))
                    out.append("\n")
                }
                t.matches(Regex("""^(-{3,}|\*{3,}|_{3,})$""")) -> {
                    val s0 = out.length
                    out.append("───────────────")
                    out.setSpan(ForegroundColorSpan(outline), s0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    out.append("\n")
                }
                t.isEmpty() -> out.append("\n")
                else -> { inline(t); out.append("\n") }
            }
            i++
        }
        flushCode()
        return out
    }

    /** TextView 应用渲染并启用链接点击。 */
    fun apply(tv: TextView, markdown: String) {
        tv.text = render(tv.context, markdown)
        tv.movementMethod = LinkMovementMethod.getInstance()
    }
}
