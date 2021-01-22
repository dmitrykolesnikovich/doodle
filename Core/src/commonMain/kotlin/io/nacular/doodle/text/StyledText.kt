package io.nacular.doodle.text

import io.nacular.doodle.drawing.Color
import io.nacular.doodle.drawing.ColorFill
import io.nacular.doodle.drawing.Fill
import io.nacular.doodle.drawing.Font
import io.nacular.doodle.text.Target.Background
import io.nacular.doodle.text.Target.Foreground
import io.nacular.doodle.text.TextDecoration.Style.Solid

/**
 * Created by Nicholas Eddy on 10/31/17.
 */
public class TextDecoration(
        public val lines    : Set<Line> = emptySet(),
        public val color    : Color? = null,
        public val style    : Style = Solid,
        public val thickNess: ThickNess? = null
) {
    public enum class Line { Under, Over, Through }
    public enum class Style { Solid, Double, Dotted, Dashed, Wavy }
    public sealed class ThickNess {
        public object FromFont: ThickNess()
        public class Absolute(public val value: Double): ThickNess()
        public class Percent (public val value: Float ): ThickNess()
    }

}

public interface Style {
    public val font      : Font?
    public val foreground: Fill?
    public val background: Fill?
    public val decoration: TextDecoration?
}

public class StyledText private constructor(internal val data: MutableList<MutablePair<String, StyleImpl>>): Iterable<Pair<String, Style>> {
    public constructor(
        text      : String,
        font      : Font?           = null,
        foreground: Fill?           = null,
        background: Fill?           = null,
        decoration: TextDecoration? = null): this(mutableListOf(MutablePair(text, StyleImpl(
            font,
            foreground = foreground,
            background = background,
            decoration = decoration
    ))))

    public data class MutablePair<A, B>(var first: A, var second: B) {
        override fun toString(): String = "($first, $second)"
    }

    public val text : String get() = data.joinToString { it.first }
    public val count: Int    get() = data.size

    private var hashCode = data.hashCode()

    override fun iterator(): Iterator<Pair<String, Style>> = data.map { it.first to it.second }.iterator()

    public operator fun plus(other: StyledText): StyledText = this.also { other.data.forEach { style -> add(style) } }

    public operator fun rangeTo(font : Font      ): StyledText = this.also { add(MutablePair("",   StyleImpl(font))) }
    public operator fun rangeTo(color: Color     ): StyledText = this.also { add(MutablePair("",   StyleImpl(foreground = ColorFill(color)))) }
    public operator fun rangeTo(text : String    ): StyledText = this.also { add(MutablePair(text, StyleImpl())) }
    public operator fun rangeTo(text : StyledText): StyledText = this.also { text.data.forEach { add(MutablePair(it.first, it.second)) } }

    public fun copy(): StyledText = StyledText(mutableListOf(*data.map { MutablePair(it.first, it.second.copy()) }.toTypedArray()))

    private fun add(pair: MutablePair<String, StyleImpl>) {
        val (_, style) = data.last()

        return when (style) {
            pair.second -> data.last().first += pair.first
            else        -> data.plusAssign(pair)
        }.also {
            hashCode = data.hashCode()
        }
    }

    override fun hashCode(): Int = hashCode

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StyledText) return false

        if (data != other.data) return false

        return true
    }

    public operator fun Font.invoke(text: StyledText): StyledText {
        text.data.forEach { (_, style) ->
            if (style.font == null) {
                style.font = this
            }
        }

        return text
    }

    internal data class StyleImpl(
            override var font      : Font? = null,
            override var foreground: Fill? = null,
            override var background: Fill? = null,
            override var decoration: TextDecoration? = null
    ): Style
}

// TODO: Change to invoke(text: () -> String) when fixed (https://youtrack.jetbrains.com/issue/KT-22119)
public operator fun Font?.invoke(text: String          ): StyledText = StyledText(text = text, font = this)
public operator fun Font?.invoke(text: () -> StyledText): StyledText = text().apply {
    data.forEach { (_, style) ->
        if (style.font == null) {
            style.font = this@invoke
        }
    }
}

//operator fun Font.get(text: String    ) = StyledText(text = text, font = this)
//operator fun Font.get(text: StyledText) = text.apply {
//    data.forEach { (_, style) ->
//        if (style.font == null) {
//            style.font = this@get
//        }
//    }
//}


public enum class Target {
    Background,
    Foreground
}

// TODO: Change to invoke(text: () -> String) when fixed (https://youtrack.jetbrains.com/issue/KT-22119)
public operator fun Color?.invoke(text: String, target: Target = Foreground): StyledText = this?.let { ColorFill(it) }.let {
    StyledText(text = text, background = if (target == Background) it else null, foreground = if (target == Foreground) it else null)
}
public operator fun Color?.invoke(text: () -> StyledText): StyledText = text().apply {
    data.forEach { (_, style) ->
        if (style.foreground == null && this@invoke != null) {
            style.foreground = ColorFill(this@invoke)
        }
    }
}

//operator fun Color.get(text: String, fill: Fill = Foreground) = ColorFill(this).let {
//    StyledText(text = text, background = if (fill == Background) it else null, foreground = if (fill == Foreground) it else null)
//}
//operator fun Color.get(text: StyledText) = text.apply {
//    data.forEach { (_, style) ->
//        if (style.foreground == null) {
//            style.foreground = ColorFill(this@get)
//        }
//    }
//}

// TODO: Change to invoke(text: () -> String) when fixed (https://youtrack.jetbrains.com/issue/KT-22119)
public operator fun TextDecoration?.invoke(text: String          ): StyledText = StyledText(text = text, decoration = this)
public operator fun TextDecoration?.invoke(text: () -> StyledText): StyledText = text().apply {
    data.forEach { (_, style) ->
        if (style.decoration == null) {
            style.decoration = this@invoke
        }
    }
}


public operator fun String.rangeTo(styled: StyledText): StyledText = StyledText(this) + styled

// "foo" .. font {  } + color { }
