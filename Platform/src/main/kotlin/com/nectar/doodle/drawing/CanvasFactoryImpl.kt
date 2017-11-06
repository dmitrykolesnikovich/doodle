package com.nectar.doodle.drawing

import com.nectar.doodle.dom.HtmlFactory
import com.nectar.doodle.dom.SvgFactoryImpl
import com.nectar.doodle.drawing.impl.CanvasImpl
import com.nectar.doodle.drawing.impl.VectorRendererSvg
import org.w3c.dom.Node


class CanvasFactoryImpl(private val htmlFactory: HtmlFactory, private val textFactory: TextFactory): CanvasFactory {
    override operator fun invoke(region: Node): Canvas {
        return CanvasImpl(region, htmlFactory, textFactory) {
            VectorRendererSvg(it, SvgFactoryImpl())
        }
    }
}