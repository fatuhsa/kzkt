package com.kzkt.app.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Minimal markdown renderer for release notes. Handles the subset GitHub /
 * CHANGELOG release bodies actually use:
 *
 *  - `#` / `##` / `###` headers (rendered as themed headings)
 *  - `- ` / `* ` bullet lists
 *  - blank-line separated paragraphs
 *  - inline `**bold**`, `` `code` ``, and `[text](url)` links
 *
 * Built on [AnnotatedString] so it stays dependency-free and matches the app
 * theme (colors/fonts come from the surrounding [MaterialTheme]).
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val blocks = remember(markdown) { parseBlocks(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is Block.Header -> MarkdownHeader(block.raw, block.level, colors)
                is Block.Bullet -> MarkdownBullet(block.raw, colors)
                is Block.Paragraph -> MarkdownBody(block.raw, colors)
            }
        }
    }
}

/** One parsed block of a markdown document (header / bullet / paragraph). */
private sealed interface Block {
    data class Header(
        val level: Int,
        val raw: String,
    ) : Block

    data class Bullet(
        val raw: String,
    ) : Block

    data class Paragraph(
        val raw: String,
    ) : Block
}

/** Pure parse step — no composables involved, safe to call from [remember]. */
private fun parseBlocks(markdown: String): List<Block> {
    val result = mutableListOf<Block>()
    var paragraph = StringBuilder()
    var paragraphPending = false

    fun flush() {
        if (paragraphPending) {
            result.add(Block.Paragraph(paragraph.toString()))
            paragraph = StringBuilder()
            paragraphPending = false
        }
    }

    for (rawLine in markdown.lines()) {
        val line = rawLine.trim()
        when {
            line.isBlank() -> flush()

            line.startsWith("#### ") -> {
                flush()
                result.add(Block.Header(4, line.removePrefix("#### ").trim()))
            }
            line.startsWith("### ") -> {
                flush()
                result.add(Block.Header(3, line.removePrefix("### ").trim()))
            }
            line.startsWith("## ") -> {
                flush()
                result.add(Block.Header(2, line.removePrefix("## ").trim()))
            }
            line.startsWith("# ") -> {
                flush()
                result.add(Block.Header(1, line.removePrefix("# ").trim()))
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                flush()
                result.add(Block.Bullet(line.drop(2).trim()))
            }
            else -> {
                if (paragraphPending) paragraph.append('\n')
                paragraph.append(line)
                paragraphPending = true
            }
        }
    }
    flush()
    return result
}

@Composable
private fun MarkdownHeader(
    raw: String,
    level: Int,
    colors: ColorScheme,
) {
    val style =
        when (level) {
            1 -> MaterialTheme.typography.titleLarge
            2 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
    Text(
        text = renderInline(raw, colors, boldWeight = FontWeight.Bold),
        style = style,
        fontWeight = FontWeight.Bold,
        color = colors.primary,
    )
}

@Composable
private fun MarkdownBullet(
    raw: String,
    colors: ColorScheme,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", style = MaterialTheme.typography.bodySmall, color = colors.primary)
        Text(
            text = renderInline(raw, colors, boldWeight = FontWeight.SemiBold),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun MarkdownBody(
    raw: String,
    colors: ColorScheme,
) {
    Text(
        text = renderInline(raw, colors, boldWeight = FontWeight.SemiBold),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
}

private val INLINE_PATTERN = Regex("""(\*\*.+?\*\*)|(`[^`]+`)|(\[[^\]]+\]\([^)]+\))""")
private val LINK_PATTERN = Regex("""\[([^\]]+)\]\(([^)]+)\)""")

/** Convert a single line's inline markers (**bold**, `code`, [text](url)) into spans. */
private fun renderInline(
    text: String,
    colors: ColorScheme,
    boldWeight: FontWeight,
): AnnotatedString {
    val bold = SpanStyle(fontWeight = boldWeight)
    val code = SpanStyle(fontFamily = FontFamily.Monospace, background = colors.surfaceVariant)
    val link = SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)

    return buildAnnotatedString {
        var index = 0
        for (match in INLINE_PATTERN.findAll(text)) {
            if (match.range.first > index) append(text, index, match.range.first)
            val token = match.value
            when {
                token.startsWith("**") -> {
                    pushStyle(bold)
                    append(token.substring(2, token.length - 2))
                    pop()
                }
                token.startsWith("`") -> {
                    pushStyle(code)
                    append(token.substring(1, token.length - 1))
                    pop()
                }
                else -> {
                    val linkMatch = LINK_PATTERN.find(token)
                    if (linkMatch != null) {
                        pushStyle(link)
                        append(linkMatch.groupValues[1])
                        pop()
                    } else {
                        append(token)
                    }
                }
            }
            index = match.range.last + 1
        }
        if (index < text.length) append(text, index, text.length)
    }
}
