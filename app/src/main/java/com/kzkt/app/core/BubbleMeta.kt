package com.kzkt.app.core

import android.graphics.Paint

data class BubbleMeta(
    val id: String,
    var text: String,
    var box: IntArray, // [x1, y1, x2, y2]
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var align: Paint.Align = Paint.Align.CENTER,
    var fontPreset: String = "Default", // e.g. "Default", "Serif", "Monospace"
    var rawText: String? = null,
    var fontScale: Float = 1.0f,
    var strokeColor: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BubbleMeta

        if (id != other.id) return false
        if (text != other.text) return false
        if (!box.contentEquals(other.box)) return false
        if (isBold != other.isBold) return false
        if (isItalic != other.isItalic) return false
        if (align != other.align) return false
        if (fontPreset != other.fontPreset) return false
        if (rawText != other.rawText) return false
        if (fontScale != other.fontScale) return false
        if (strokeColor != other.strokeColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + box.contentHashCode()
        result = 31 * result + isBold.hashCode()
        result = 31 * result + isItalic.hashCode()
        result = 31 * result + align.hashCode()
        result = 31 * result + fontPreset.hashCode()
        result = 31 * result + (rawText?.hashCode() ?: 0)
        result = 31 * result + fontScale.hashCode()
        result = 31 * result + (strokeColor?.hashCode() ?: 0)
        return result
    }
}
