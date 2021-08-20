package io.nacular.doodle.drawing

import io.nacular.doodle.drawing.GradientPaint.Stop
import io.nacular.doodle.geometry.Circle
import io.nacular.doodle.geometry.Point

/**
 * A gradient [Paint] that transitions between a list of [Stop]s.
 *
 * @property colors at stop points along the transition
 *
 * @constructor
 * @param colors at stop points
 */
public sealed class GradientPaint(public val colors: List<Stop>): Paint() {
    public class Stop(public val color: Color, public val offset: Float)

    /**
     * Creates a fill with a gradient between the given colors.
     *
     * @param color1 associated with the start point
     * @param color2 associated with the end point
     */
    protected constructor(color1: Color, color2: Color): this(listOf(Stop(color1, 0f), Stop(color2, 1f)))

    /** `true` IFF any one of [colors] is visible */
    override val visible: Boolean = colors.any { it.color.visible }
}

/**
 * A linear gradient [Paint] that transitions between a list of [Stop]s.
 *
 * Created by Nicholas Eddy on 11/5/18.
 *
 * @property start of the line along which the gradient flows
 * @property end of the line along which the gradient flows
 *
 * @constructor
 * @param colors at stop points
 * @param start of the line along which the gradient flows
 * @param end of the line along which the gradient flows
 */
public class LinearGradientPaint(colors: List<Stop>, public val start: Point, public val end: Point): GradientPaint(colors) {
    /**
     * Creates a fill with a gradient between the given colors.
     *
     * @param color1 associated with the start point
     * @param color2 associated with the end point
     * @param start of the line along which the gradient flows
     * @param end of the line along which the gradient flows
     */
    public constructor(color1: Color, color2: Color, start: Point, end: Point): this(listOf(Stop(color1, 0f), Stop(color2, 1f)), start, end)

    /** `true` IFF super visible and start != end */
    override val visible: Boolean = super.visible && start != end
}

/**
 * A radial gradient [Paint] that transitions between a list of [Stop]s.
 *
 * @property start circle from which the gradient flows
 * @property end circle that the gradient stops at
 *
 * @constructor
 * @param colors at stop points
 * @param start circle from which the gradient flows
 * @param end circle that the gradient stops at
 */
public class RadialGradientPaint(colors: List<Stop>, public val start: Circle, public val end: Circle): GradientPaint(colors) {
    /**
     * Creates a fill with a gradient between the given colors.
     *
     * @param color1 associated with the start point
     * @param color2 associated with the end point
     * @param start circle from which the gradient flows
     * @param end circle that the gradient stops at
     */
    public constructor(color1: Color, color2: Color, start: Circle, end: Circle): this(listOf(Stop(color1, 0f), Stop(color2, 1f)), start, end)
}