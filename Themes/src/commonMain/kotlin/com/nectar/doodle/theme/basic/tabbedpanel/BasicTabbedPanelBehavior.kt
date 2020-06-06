package com.nectar.doodle.theme.basic.tabbedpanel

import com.nectar.doodle.animation.Animator
import com.nectar.doodle.animation.fixedSpeedLinear
import com.nectar.doodle.animation.fixedTimeLinear
import com.nectar.doodle.controls.panels.TabbedPanel
import com.nectar.doodle.controls.panels.TabbedPanelBehavior
import com.nectar.doodle.core.Layout
import com.nectar.doodle.core.PositionableContainer
import com.nectar.doodle.core.View
import com.nectar.doodle.drawing.AffineTransform.Companion.Identity
import com.nectar.doodle.drawing.Canvas
import com.nectar.doodle.drawing.Color
import com.nectar.doodle.drawing.Color.Companion.Black
import com.nectar.doodle.drawing.Color.Companion.Gray
import com.nectar.doodle.drawing.Color.Companion.White
import com.nectar.doodle.drawing.ColorBrush
import com.nectar.doodle.drawing.Pen
import com.nectar.doodle.drawing.TextMetrics
import com.nectar.doodle.drawing.lighter
import com.nectar.doodle.event.PointerEvent
import com.nectar.doodle.event.PointerListener
import com.nectar.doodle.event.PointerMotionListener
import com.nectar.doodle.geometry.Path
import com.nectar.doodle.geometry.Point
import com.nectar.doodle.geometry.Rectangle
import com.nectar.doodle.geometry.Size
import com.nectar.doodle.geometry.path
import com.nectar.doodle.layout.Insets
import com.nectar.doodle.system.Cursor
import com.nectar.doodle.utils.Cancelable
import com.nectar.doodle.utils.addOrAppend
import com.nectar.measured.units.Time
import com.nectar.measured.units.div
import com.nectar.measured.units.times
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Created by Nicholas Eddy on 3/14/19.
 */

abstract class Tab<T>: View() {
    abstract var index: Int
}

open class BasicTab<T>(private  val textMetrics  : TextMetrics,
                       private  val panel        : TabbedPanel<T>,
                       override var index        : Int,
                       private  val name         : String,
                       private  val radius       : Double,
                       private  val tabColor     : Color,
                       private  val selectedColor: Color,
                       private  val move         : (panel: TabbedPanel<T>, tab: Int, by: Double) -> Unit,
                       private  val cancelMove   : (panel: TabbedPanel<T>, tab: Int) -> Unit): Tab<T>() {
    private var pointerOver = false
        set(new) {
            field = new
            backgroundColor = when {
                selected  -> selectedColor
                new       -> tabColor.lighter()
                else      -> tabColor
            }
        }

    private var pointerDown     = false
    private var initialPosition = null as Point?

    private val selected get() = panel.selection == index

    private var path: Path

    private val selectionChanged = { _: TabbedPanel<T>, old: Int?, new: Int? ->
        backgroundColor = when {
            old == index -> if (pointerOver) tabColor.lighter() else null
            new == index -> selectedColor
            else         -> null
        }
    }

    init {
        pointerChanged += object: PointerListener {
            override fun pressed (event: PointerEvent) { pointerDown = true; panel.selection = index; initialPosition = toLocal(event.location, event.target) }
            override fun entered (event: PointerEvent) { pointerOver = true  }
            override fun exited  (event: PointerEvent) { pointerOver = false }
            override fun released(event: PointerEvent) {
                if (pointerDown) {
                    pointerDown = false
                    cursor    = null

                    cancelMove(panel, index)
                }

                initialPosition = null
            }
        }

        pointerMotionChanged += object: PointerMotionListener {
            override fun dragged(event: PointerEvent) {
                initialPosition?.let {
                    val delta = (toLocal(event.location, event.target) - it).x

                    move(panel, index, delta)

                    cursor = Cursor.Grabbing

                    event.consume()
                }
            }
        }

        boundsChanged += { _,_,_ -> path = updatePath() }

        styleChanged += { rerender() }

        panel.selectionChanged += selectionChanged

        if (selected) {
            backgroundColor = selectedColor
        }

        path = updatePath()
    }

    override fun removedFromDisplay() {
        super.removedFromDisplay()

        panel.selectionChanged -= selectionChanged
    }

    override fun render(canvas: Canvas) {
        val selection = panel.selection

        backgroundColor?.let {
            canvas.path(path, ColorBrush(it))
        } ?: when {
            selection != null && (index > selection || index < selection - 1) -> {
                canvas.line(Point(width - radius, radius), Point(width - radius, height - radius), Pen(Gray))
            }
        }

        canvas.clip(Rectangle(Point(2 * radius, 0.0), Size(width - 4 * radius, height))) {
            val name       = name
            val nameHeight = textMetrics.height(name)

            text(name, at = Point(2 * radius, (height - nameHeight) / 2), brush = ColorBrush(Black))
        }
    }

    override fun contains(point: Point) = super.contains(point) && when (val localPoint = toLocal(point, parent)) {
        in Rectangle(radius, 0.0, width - 2 * radius, height) -> when (localPoint) {
            in Rectangle(            radius, 0.0, radius, radius)     -> sqrt((Point(        2 * radius, radius) - localPoint).run { x * x + y * y }) <= radius
            in Rectangle(width - 2 * radius, 0.0, radius, radius)     -> sqrt((Point(width - 2 * radius, radius) - localPoint).run { x * x + y * y }) <= radius
            else                                                      -> true
        }
        in Rectangle(           0.0, height - radius, radius, radius) -> sqrt((Point(  0.0, height - radius) - localPoint).run { x * x + y * y }) >  radius
        in Rectangle(width - radius, height - radius, radius, radius) -> sqrt((Point(width, height - radius) - localPoint).run { x * x + y * y }) >  radius
        else                                                          -> false
    }

    private fun updatePath() = path(Point(0.0, height)).
            quadraticTo(Point(radius,             height - radius), Point(radius,         height)).
            lineTo     (Point(radius,             radius                                        )).
            quadraticTo(Point(2 * radius,         0.0            ), Point(radius,         0.0   )).
            lineTo     (Point(width - 2 * radius, 0.0                                           )).
            quadraticTo(Point(width - radius,     radius         ), Point(width - radius, 0.0   )).
            lineTo     (Point(width - radius,     height - radius                               )).
            quadraticTo(Point(width,              height         ), Point(width - radius, height)).
            close      ()
}

interface TabProducer<T> {
    val spacing  : Double
    val tabHeight: Double

    operator fun invoke(panel: TabbedPanel<T>, item: T, index: Int): Tab<T>
}

typealias TabNamer<T> = (TabbedPanel<T>, T, Int) -> String

open class BasicTabProducer<T>(protected val textMetrics  : TextMetrics,
                               protected val namer        : TabNamer<T>,
                               override  val tabHeight    : Double = 40.0,
                               protected val tabRadius    : Double = 10.0,
                               protected val selectedColor: Color  = White,
                               protected val tabColor     : Color  = Color(0xdee1e6u)): TabProducer<T> {
    override val spacing = -2 * tabRadius

    override fun invoke(panel: TabbedPanel<T>, item: T, index: Int) = BasicTab(textMetrics, panel, index, namer(panel, item, index), tabRadius, tabColor, selectedColor, move, cancelMove).apply { size = Size(100.0, tabHeight) } // FIXME: use dynamic width

    protected open val move = { _: TabbedPanel<T>, _: Int,_: Double -> }

    protected open val cancelMove = { _: TabbedPanel<T>, _: Int -> }
}

private class TabLayout(private val minWidth: Double = 40.0, private val defaultWidth: Double = 200.0, private val spacing: Double = 0.0): Layout {
    override fun layout(container: PositionableContainer) {
        val maxLineWidth = max(0.0, container.width - container.insets.left - container.insets.right - (container.children.size - 1) * spacing)

        var x     = container.insets.left
        val width = max(minWidth, min(defaultWidth, maxLineWidth / container.children.size))

        container.children.filter { it.visible }.forEach { child ->
            child.width    = width
            child.position = Point(x, container.insets.top)

            x += width + spacing
        }
    }
}

abstract class TabContainer<T>: View() {
    /**
     * Called whenever the TabbedPanel's selection changes. This is an explicit API to ensure that
     * behaviors receive the notification before listeners to [TabbedPanel.selectionChanged].
     *
     * @param panel with change
     * @param newIndex of the selected item
     * @param oldIndex of previously selected item
     */
    abstract fun selectionChanged(panel: TabbedPanel<T>, newIndex: Int?, oldIndex: Int?)

    /**
     * Called whenever the items within the TabbedPanel change.
     *
     * @param panel with change
     * @param removed items
     * @param added items
     * @param moved items (changed index)
     */
    abstract fun itemsChanged(panel: TabbedPanel<T>, removed: Map<Int, T>, added: Map<Int, T>, moved: Map<Int, Pair<Int, T>>)
}

open class SimpleTabContainer<T>(panel: TabbedPanel<T>, private val tabProducer: TabProducer<T>): TabContainer<T>() {
    init {
        children.addAll(panel.mapIndexed { index, item ->
            tabProducer(panel, item, index)
        })

        insets = Insets(top = 10.0) // TODO: Make this configurable
        layout = TabLayout(spacing = tabProducer.spacing)
    }

    override fun selectionChanged(panel: TabbedPanel<T>, newIndex: Int?, oldIndex: Int?) {
        oldIndex?.let { children.getOrNull(it) }?.let { it.zOrder = 0; it.rerender() }
        newIndex?.let { children.getOrNull(it) }?.let { it.zOrder = 1; it.rerender() }
    }

    override fun itemsChanged(panel: TabbedPanel<T>, removed: Map<Int, T>, added: Map<Int, T>, moved: Map<Int, Pair<Int, T>>) {
        children.batch {
            removed.keys.forEach { removeAt(it) }

            added.forEach { (index, item) ->
                addOrAppend(index, tabProducer(panel, item, index))
            }

            moved.forEach { (new, old) ->
                addOrAppend(new, removeAt(old.first))
            }

            filterIsInstance<Tab<T>>().forEachIndexed { index, item ->
                item.index = index
            }
        }
    }
}

class AnimatingTabContainer<T>(
        private val animate: Animator,
        private val panel: TabbedPanel<T>,
        private val tabProducer: TabProducer<T>): SimpleTabContainer<T>(panel, tabProducer) {
    private val animations = mutableMapOf<View, Cancelable>()

    init {
        childrenChanged += { _,_,added,_ ->
            added.values.filterIsInstance<Tab<T>>().forEach { tagTab(panel, it) }
        }

        children.filterIsInstance<Tab<T>>().forEach { tagTab(panel, it) }
    }

    private fun tagTab(panel: TabbedPanel<T>, tab: Tab<T>) = tab.apply {
        var pointerDown     = false
        var initialPosition = null as Point?

        pointerChanged += object: PointerListener {
            override fun pressed (event: PointerEvent) { pointerDown = true; initialPosition = toLocal(event.location, event.target); cleanupAnimation(this@apply) }
            override fun entered (event: PointerEvent) { doAnimation(panel, this@apply, 0f, 1f) }
            override fun exited  (event: PointerEvent) { doAnimation(panel, this@apply, 1f, 0f) }
            override fun released(event: PointerEvent) {
                if (pointerDown) {
                    pointerDown = false
                    cancelMove(panel, index)
                }

                initialPosition = null
            }
        }

        pointerMotionChanged += object: PointerMotionListener {
            override fun dragged(event: PointerEvent) {
                initialPosition?.let {
                    val delta = (toLocal(event.location, event.target) - it).x

                    move(panel, index, delta)

                    cursor = Cursor.Grabbing

                    event.consume()
                }
            }
        }
    }

    private fun cleanupAnimation(tab: View) {
        animations[tab]?.let {
            it.cancel()
            animations.remove(tab)
        }
    }

    private fun move(panel: TabbedPanel<T>, movingIndex: Int, delta: Double) {
        children.getOrNull(movingIndex)?.apply {
            zOrder         = 1
            val translateX = transform.translateX
            val delta      = min(max(delta, 0 - (x + translateX)), panel.width - width - (x + translateX))

            transform *= Identity.translate(delta)

            val adjustWidth = width + tabProducer.spacing

            children.forEachIndexed { index, tab ->
                if (tab != this) {
                    val targetBounds = tab.bounds

                    val value = when (targetBounds.x + tab.transform.translateX + targetBounds.width / 2) {
                        in x + translateX + delta            .. x + translateX                    ->  adjustWidth
                        in x + translateX                    .. x + translateX + delta            -> -adjustWidth
                        in bounds.right + translateX         .. bounds.right + translateX + delta -> -adjustWidth
                        in bounds.right + translateX + delta .. bounds.right + translateX         ->  adjustWidth
                        else                                                                      ->  null
                    }

                    value?.let {
                        val oldTransform = tab.transform
                        val minViewX     = if (index > movingIndex) tab.x - adjustWidth else tab.x
                        val maxViewX     = minViewX + adjustWidth
                        val offset       = tab.x + tab.transform.translateX
                        val translate    = min(max(value, minViewX - offset), maxViewX - offset)

//                        tab.animation = behavior?.moveColumn(this@Table) {
                        animate { (0f to 1f using fixedSpeedLinear(10 / Time.seconds)) {
                            tab.transform = oldTransform.translate(translate * it) //1.0)
                        } }
                    }
                }
            }
        }
    }

    private fun cancelMove(panel: TabbedPanel<T>, movingIndex: Int) {
        children.filterIsInstance<Tab<*>>().getOrNull(movingIndex)?.apply {
            zOrder           = 0
            val myOffset     = x + transform.translateX
            var myNewIndex   = if (myOffset >= children.last().x) children.size - 1 else movingIndex
            var targetBounds = bounds
            val numChildren  = children.size

            run loop@ {
                children.forEachIndexed { index, tab ->
                    val targetMiddle = tab.x + tab.transform.translateX + tab.width / 2

                    if ((transform.translateX < 0 && myOffset < targetMiddle) ||
                        (transform.translateX > 0 && ((myOffset + width < targetMiddle) || index == numChildren - 1))) {
                        myNewIndex   = index - if (this.index < index) 1 else 0 // Since tab will be removed and added to index
                        targetBounds = children[myNewIndex].bounds
                        return@loop
                    }
                }
            }

            val oldTransform = transform

//            animation = behavior?.moveColumn(table) {
                transform = when {
                    index < myNewIndex -> oldTransform.translate((targetBounds.right - width - myOffset) * 1f)
                    else               -> oldTransform.translate((targetBounds.x             - myOffset) * 1f)
                }
//            }?.apply {
//                completed += {
                    if (index != myNewIndex) {
                        panel[movingIndex]?.let { panel.move(it, to = myNewIndex) }
                    }

                    children.forEach { it.transform = Identity }
//                }
//            }
        }
    }

    private fun <T> doAnimation(panel: TabbedPanel<T>, tab: Tab<T>, start: Float, end: Float) {
        cleanupAnimation(tab)

        if (panel.selection == tab.index) {
            return
        }

        val tabColor = tab.backgroundColor

        animations[tab] = (animate (start to end) using fixedTimeLinear(250 * Time.milliseconds)) {
            tab.backgroundColor = tabColor?.lighter()?.opacity(it)

            tab.rerenderNow()
        }.apply {
            completed += { animations.remove(tab) }
        }
    }
}

typealias TabContainerFactory<T> = (TabbedPanel<T>, TabProducer<T>) -> TabContainer<T>

open class BasicTabbedPanelBehavior<T>(
        private val tabProducer    : TabProducer<T>,
        private val backgroundColor: Color = Color(0xdee1e6u),
        private val tabContainer   : TabContainerFactory<T> = { panel, tabProducer -> SimpleTabContainer(panel, tabProducer) }): TabbedPanelBehavior<T>() {

    override fun install(view: TabbedPanel<T>) {
        view.apply {
            children += tabContainer(view, tabProducer)

            view.forEach {
                children.add(view.visualizer(it).apply {
                    visible = it == view.selectedItem
                })
            }

            layout = object: Layout {
                override fun layout(container: PositionableContainer) {
                    container.children.forEachIndexed { index, view ->
                        view.bounds = when (index) {
                            0    -> Rectangle(container.width, tabProducer.tabHeight + 10)
                            else -> Rectangle(size = container.size).inset(Insets(top = tabProducer.tabHeight + 10))
                        }
                    }
                }
            }
        }
    }

    override fun uninstall(view: TabbedPanel<T>) {
        view.apply {
            children.clear()
            layout = null
        }
    }

    override fun selectionChanged(panel: TabbedPanel<T>, new: T?, newIndex: Int?, old: T?, oldIndex: Int?) {
        val dirty = mutableSetOf<Int>()

        oldIndex?.let {
            panel.children.getOrNull(it + 1)?.visible = false

            dirty += it
        }

        newIndex?.let {
            panel.children.getOrNull(it + 1)?.visible = true

            dirty += it
        }

        (panel.children[0] as? TabContainer<T>)?.let {
            it.selectionChanged(panel, newIndex, oldIndex)
        }
    }

    override fun itemsChanged(panel: TabbedPanel<T>, removed: Map<Int, T>, added: Map<Int, T>, moved: Map<Int, Pair<Int, T>>) {
        (panel.children[0] as? TabContainer<T>)?.apply {
            itemsChanged(panel, removed, added, moved)
        }

        removed.keys.forEach { panel.children.removeAt(it + 1) }

        added.forEach { (index, item) ->
            panel.children.addOrAppend(index + 1, panel.visualizer(item))
        }

        moved.forEach { (new, old) ->
            panel.children.addOrAppend(new + 1, panel.children.removeAt(old.first + 1))
        }
    }

    override fun render(view: TabbedPanel<T>, canvas: Canvas) {
        canvas.rect(view.bounds.atOrigin, ColorBrush(backgroundColor))
    }
}