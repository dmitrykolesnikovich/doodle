package com.nectar.doodle.drawing

import com.nectar.doodle.dom.ElementRuler
import com.nectar.doodle.geometry.Size
import com.nectar.doodle.text.StyledText


private data class WrappedInfo     (val text: String,     val width: Double, val indent: Double, val font: Font?)
private data class WrappedStyleInfo(val text: StyledText, val width: Double, val indent: Double                 )

class TextMetricsImpl(private val textFactory: TextFactory, private val elementRuler: ElementRuler): TextMetrics {
    private val sizes              = mutableMapOf<Pair<String, Font?>, Size>()
    private val styledSizes        = mutableMapOf<StyledText, Size>()
    private val wrappedSizes       = mutableMapOf<WrappedInfo, Size>()
    private val wrappedStyledSizes = mutableMapOf<WrappedStyleInfo, Size>()

    override fun width (text: String, font: Font?) = size(text, font).width
    override fun height(text: String, font: Font?) = size(text, font).height

    override fun size(text: String, font: Font?) = sizes.getOrPut(Pair(text, font)) {
        elementRuler.size(textFactory.create(text, font))
    }

    override fun size(text: String, width: Double, indent: Double, font: Font?) = wrappedSizes.getOrPut(WrappedInfo(text, width, indent, font)) {
        elementRuler.size(textFactory.wrapped(text, font, width, indent))
    }

    override fun width (text: StyledText) = size(text).width
    override fun height(text: StyledText) = size(text).height

    override fun size(text: StyledText) = styledSizes.getOrPut(text) {
        elementRuler.size(textFactory.create(text))
    }

    override fun size(text: StyledText, width: Double, indent: Double) = wrappedStyledSizes.getOrPut(WrappedStyleInfo(text, width, indent)) {
        Size(width = width, height = elementRuler.height(textFactory.wrapped(text, width, indent)))
    }
}