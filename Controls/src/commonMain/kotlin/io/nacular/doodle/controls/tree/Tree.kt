@file:Suppress("NestedLambdaShadowedImplicitParameter")

package io.nacular.doodle.controls.tree

import io.nacular.doodle.JvmName
import io.nacular.doodle.accessibility.TreeRole
import io.nacular.doodle.controls.IndexedItem
import io.nacular.doodle.controls.ItemVisualizer
import io.nacular.doodle.controls.Selectable
import io.nacular.doodle.controls.SelectionModel
import io.nacular.doodle.controls.theme.TreeBehavior
import io.nacular.doodle.controls.theme.TreeBehavior.RowGenerator
import io.nacular.doodle.controls.theme.TreeBehavior.RowPositioner
import io.nacular.doodle.core.ContentDirection
import io.nacular.doodle.core.Layout
import io.nacular.doodle.core.PositionableContainer
import io.nacular.doodle.core.View
import io.nacular.doodle.core.behavior
import io.nacular.doodle.core.scrollTo
import io.nacular.doodle.drawing.Canvas
import io.nacular.doodle.geometry.Point
import io.nacular.doodle.geometry.Point.Companion.Origin
import io.nacular.doodle.geometry.Rectangle
import io.nacular.doodle.geometry.Rectangle.Companion.Empty
import io.nacular.doodle.geometry.Size
import io.nacular.doodle.layout.Constraints
import io.nacular.doodle.layout.Insets
import io.nacular.doodle.utils.Path
import io.nacular.doodle.utils.Pool
import io.nacular.doodle.utils.PropertyObservers
import io.nacular.doodle.utils.SetObserver
import io.nacular.doodle.utils.SetPool
import io.nacular.doodle.utils.observable
import kotlin.math.max
import kotlin.math.min


/**
 * Created by Nicholas Eddy on 3/23/18.
 */

public typealias ExpansionObserver<T>  = (source: Tree<T, *>, paths: Set<Path<Int>>) -> Unit
public typealias ExpansionObservers<T> = SetPool<ExpansionObserver<T>>

public interface TreeLike: Selectable<Path<Int>> {
    public val numRows    : Int
    public val hasFocus   : Boolean
    public val rootVisible: Boolean

    public val focusChanged: PropertyObservers<View, Boolean>

    public val contentDirection: ContentDirection

    public fun visible(row: Int): Boolean
    public fun visible(path: Path<Int>): Boolean

    public fun isLeaf(path: Path<Int>): Boolean

    public fun expanded(path: Path<Int>): Boolean

    public fun pathFromRow(index: Int): Path<Int>?
    public fun rowFromPath(path: Path<Int>): Int?

    public fun selected       (row : Int     ): Boolean = pathFromRow(row)?.let { selected(it) } ?: false
    // FIXME: Hack to work around https://youtrack.jetbrains.com/issue/KT-32032
    public fun addSelection   (rows: Set<Int>, jvmWorkaround: Int = 0): Unit = addSelection   (rows.asSequence().map { pathFromRow(it) }.filterNotNull().toSet())
    public fun toggleSelection(rows: Set<Int>, jvmWorkaround: Int = 0): Unit = toggleSelection(rows.asSequence().map { pathFromRow(it) }.filterNotNull().toSet())
    public fun setSelection   (rows: Set<Int>, jvmWorkaround: Int = 0): Unit = setSelection   (rows.asSequence().map { pathFromRow(it) }.filterNotNull().toSet())
    public fun removeSelection(rows: Set<Int>, jvmWorkaround: Int = 0): Unit = removeSelection(rows.asSequence().map { pathFromRow(it) }.filterNotNull().toSet())
    public fun collapse(path: Path<Int>)
    public fun expand(path: Path<Int>)
    public fun expandAll()
    public fun collapseAll()
}

public open class Tree<T, out M: TreeModel<T>>(
        protected open val model         : M,
        public         val itemVisualizer: ItemVisualizer<T, IndexedItem>? = null,
        protected      val selectionModel: SelectionModel<Path<Int>>?     = null, // TODO: Use filtered SelectionModel to avoid selecting hidden items?
        private        val scrollCache   : Int                            = 0): View(TreeRole()), TreeLike {

    override var rootVisible: Boolean = false
        set(new) {
            if (field == new) { return }

            field = new

            // TODO: make more efficient?

            children.batch {
                clear     ()
                rowToPath.clear()
                refreshAll()
            }
        }

    private var numRows_ = 0

    override val numRows: Int get() = numRows_

    public override var insets: Insets
        get(   ) = super.insets
        set(new) { super.insets = new }

    public var behavior: TreeBehavior<T>? by behavior { _,new ->
        new?.also {
            this.generator     = it.generator
            this.rowPositioner = it.positioner

            children.batch {
                clear     ()
                refreshAll()
            }
        }
    }

    public val expanded        : ExpansionObservers<T>                    by lazy { ExpansionObserversImpl(this) }
    public val collapsed       : ExpansionObservers<T>                    by lazy { ExpansionObserversImpl(this) }
    public val selectionChanged: Pool<SetObserver<Tree<T, M>, Path<Int>>> by lazy { SetPool() }

    /**
     * Defines how the contents of an item should be aligned within it.
     */
    public var cellAlignment: (Constraints.() -> Unit)? by observable(null) { _,_ ->
        children.batch {
            (firstVisibleRow .. lastVisibleRow).asSequence().mapNotNull { pathFromRow(it) }.forEach {
                update(this, it)
            }
        }
    }

    override val firstSelection : Path<Int>?     get() = selectionModel?.first
    override val lastSelection  : Path<Int>?     get() = selectionModel?.last
    override val selectionAnchor: Path<Int>?     get() = selectionModel?.anchor
    override val selection      : Set<Path<Int>> get() = selectionModel?.toSet() ?: emptySet()
    override val firstSelectable: Path<Int>?     get() = pathFromRow(firstVisibleRow)
    override val lastSelectable : Path<Int>?     get() = pathFromRow(lastVisibleRow)

    private   var generator    : RowGenerator<T>? = null
    protected var rowPositioner: RowPositioner<T>? = null
    private   val expandedPaths      = mutableSetOf<Path<Int>>(Path())
    private   val rowToPath          = mutableMapOf<Int, Path<Int>>()
    private   var minVisiblePosition = Origin
    private   var maxVisiblePosition = Origin
    private   var minHeight          = 0.0
        set(new) {
            field       = new
            height      = field
            minimumSize = Size(minimumSize.width, field)
        }

//    private val pathToRow = mutableMapOf<Path<Int>, Int>()

    protected var firstVisibleRow: Int =  0
    protected var lastVisibleRow : Int = -1

    @Suppress("PrivatePropertyName")
    private val selectionChanged_: SetObserver<SelectionModel<Path<Int>>, Path<Int>> = { _,removed,added ->
        (selectionChanged as SetPool).forEach {
            it(this, removed, added)
        }

        children.batch {
            (firstVisibleRow .. lastVisibleRow).asSequence().mapNotNull { pathFromRow(it) }.filter { it in removed || it in added }.forEach {
                update(this, it)
            }
        }
    }

    init {
        sizePreferencesChanged += { _,_,_ ->
            idealSize = minimumSize
        }

        monitorsDisplayRect = true

        updateNumRows()

        @Suppress("LeakingThis")
        layout = object: Layout {
            override fun layout(container: PositionableContainer) {
                (firstVisibleRow .. lastVisibleRow).asSequence().mapNotNull { pathFromRow(it)?.run { it to this } }.forEach { (index, path) ->
                    model[path].onSuccess { value ->
                        children.getOrNull(index % children.size)?.let { child ->
                            layout(child, value, path, index)
                        }
                    }
                }
            }
        }
    }

    public override var isFocusCycleRoot: Boolean = true

    override fun render(canvas: Canvas) {
        behavior?.render(this, canvas)
    }

    override fun addedToDisplay() {
        selectionModel?.let { it.changed += selectionChanged_ }

        super.addedToDisplay()
    }

    override fun removedFromDisplay() {
        selectionModel?.let { it.changed -= selectionChanged_ }

        super.removedFromDisplay()
    }

    override fun handleDisplayRectEvent(old: Rectangle, new: Rectangle) {
        rowPositioner?.let { positioner ->
            if (maxVisiblePosition.x > new.right && maxVisiblePosition.y > new.bottom && minVisiblePosition.x < new.x && minVisiblePosition.y < new.y) {
                return
            }

            val oldFirst = firstVisibleRow
            val oldLast  = lastVisibleRow

            var position = new.position

            firstVisibleRow = when {
                position == old.position && !old.empty -> firstVisibleRow
                else                                   -> max(0, findRow(position, firstVisibleRow) - scrollCache)
            }

            position = Point(new.right, new.bottom)

            lastVisibleRow = when {
                position == Point(old.right, old.bottom) && !old.empty -> lastVisibleRow
                else                                                   -> min(numRows, findRow(position, lastVisibleRow) + scrollCache)
            }

            pathFromRow(firstVisibleRow)?.let { path -> model[path].onSuccess { minVisiblePosition = positioner.rowBounds(this, it, path, firstVisibleRow).position                     } }
            pathFromRow(lastVisibleRow )?.let { path -> model[path].onSuccess { maxVisiblePosition = positioner.rowBounds(this, it, path, lastVisibleRow ).run { Point(right, bottom) } } }

            children.batch {
                if (oldFirst > firstVisibleRow) {
                    val end = min(oldFirst, lastVisibleRow)

                    (firstVisibleRow until end).asSequence().mapNotNull { pathFromRow(it)?.run { it to this } }.forEach { (index, path) ->
                        insert(this, path, index)
                    }
                }

                if (oldLast < lastVisibleRow) {
                    val start = when {
                        oldLast > firstVisibleRow -> oldLast + 1
                        else                      -> firstVisibleRow
                    }

                    (start .. lastVisibleRow).asSequence().mapNotNull { pathFromRow(it)?.run { it to this } }.forEach { (index, path) ->
                        insert(this, path, index)?.let {
                            if (it > lastVisibleRow) {
                                return@forEach
                            }
                        } ?: return@forEach
                    }
                }

                // this updates "hashing" of rows into the children list (using % of list size) since the children size has changed
                if (oldLast - oldFirst != lastVisibleRow - firstVisibleRow) {
                    (firstVisibleRow .. lastVisibleRow).asSequence().mapNotNull { pathFromRow(it)?.run { it to this } }.forEach { (index, path) ->
                        update(children, path, index)
                    }
                }
            }
        }
    }

    public operator fun get(path: Path<Int>): Result<T> = model[path]

    public operator fun get(row: Int): Result<T> = pathFromRow(row)?.let { model[it] } ?: Result.failure(IllegalArgumentException())

    override fun isLeaf(path: Path<Int>): Boolean = model.isLeaf(path)

    public fun children(parent: Path<Int>): Iterator<T> = model.children(parent)

    public fun child       (of    : Path<Int>, path : Int): Result<T> = model.child       (of,     path )
    public fun numChildren (of    : Path<Int>            ): Int       = model.numChildren (of           )
    public fun indexOfChild(parent: Path<Int>, child: T  ): Int       = model.indexOfChild(parent, child)

    override fun expanded(path: Path<Int>): Boolean = path in expandedPaths

    @JvmName("expandRows") public fun expand(row : Int     ): Unit = expand(setOf(row))
    @JvmName("expandRows") public fun expand(rows: Set<Int>): Unit = expand(rows.asSequence().map { pathFromRow(it) }.filterNotNull().toSet())

    override fun expand(path: Path<Int>): Unit = expand(setOf(path))

    public fun expand(paths: Set<Path<Int>>) {
        val pathSet = paths.asSequence().filter { !expanded(it) }.sortedWith(PathComparator.then(DepthComparator)).toSet()

        val pathsToUpdate = mutableSetOf<Path<Int>>()

        children.batch {
            pathSet.forEach {
                expandedPaths += it

                if (visible(it)) {
                    pathsToUpdate -= it

                    numRows_  += rowsBelow(it)
                    minHeight += rowPositioner?.minimumSize(this@Tree, below = it)?.height ?: 0.0

                    update        (this, it)
                    insertChildren(this, it)

                    pathsToUpdate += ancestralSiblingsAfter(it)
                }
            }

            pathsToUpdate.forEach {
                updateRecursively(this, it)
            }
        }

        if (maxVisiblePosition.y < displayRect.bottom || maxVisiblePosition.x < displayRect.right) {
            // TODO: Can this be done better?  It feels a bit hacky
            handleDisplayRectEvent(Rectangle(0.0, minVisiblePosition.y, width, maxVisiblePosition.y - minVisiblePosition.y), displayRect)
        }

        expandedPaths.addAll(paths)

        if (pathSet.isNotEmpty()) {
            (expanded as ExpansionObserversImpl)(pathSet)
        }
    }

    override fun expandAll() {
        val pathsToExpand = HashSet<Path<Int>>()

        expandAllBelowPath(Path(), pathsToExpand)

        expand(pathsToExpand)
    }

    @JvmName("collapseRows") public fun collapse(row : Int     ): Unit = collapse(setOf(row))
    @JvmName("collapseRows") public fun collapse(rows: Set<Int>): Unit = collapse(rows.asSequence().map { pathFromRow(it) }.filterNotNull().toSet())

    override fun collapse(path : Path<Int>): Unit = collapse(setOf(path))
    public fun collapse(paths: Set<Path<Int>>) {
        var empty   = true
        val pathSet = paths.asSequence().filter { expanded(it) && (it.depth > 0 || rootVisible) }.sortedWith(PathComparator.thenDescending(DepthComparator)).toSet()

        children.batch {
            expandedPaths -= pathSet
            pathSet.firstOrNull { visible(it) }?.let {
                empty = false

                update(this, it)

                ancestralSiblingsAfter(it).forEach {
                    updateRecursively(this, it)
                }

                updateNumRows()

                // FIXME: This should be handled better
                minHeight = getMinHeight()

                if (maxVisiblePosition.y < displayRect.bottom || maxVisiblePosition.x < displayRect.right) {
                    // TODO: Can this be done better?  It feels a bit hacky
                    handleDisplayRectEvent(displayRect, Rectangle(0.0, minVisiblePosition.y, width, maxVisiblePosition.y - minVisiblePosition.y))
                }

                // Remove old children
                (numRows until size).forEach {
                    removeAt(numRows)
                    rowToPath.remove(it) //?.let { pathToRow.remove(it) }
                }
            }
        }

        if (!empty) {
            // Move selection up to first visible ancestor
            // TODO: Make this more efficient?  Add batch to SelectionModel?
            selectionModel?.forEach {
                if (!visible(it)) {
                    var parent = it.parent

                    while (parent != null && !visible(parent)) {
                        parent = parent.parent
                    }

                    removeSelection(setOf(it))
                    parent?.let { addSelection(setOf(it)) }
                }
            }
        }

        if (!empty) {
            (collapsed as ExpansionObserversImpl)(pathSet)
        }
    }

    override fun collapseAll(): Unit = collapse(expandedPaths)

    override fun selected(row : Int      ): Boolean = pathFromRow(row)?.let { selected(it) } ?: false
    override fun selected(item: Path<Int>): Boolean = selectionModel?.contains(item) ?: false

    override fun selectAll() {
        selectionModel?.addAll((0 .. numRows).mapNotNull {
            pathFromRow(it)
        }.toList())
    }

    override fun addSelection(items: Set<Path<Int>>) {
        selectionModel?.addAll(items.filter { visible(it) })

        scrollToSelection()
    }

    override fun toggleSelection(items: Set<Path<Int>>) {
        selectionModel?.toggle(items.filter { visible(it) })

        scrollToSelection()
    }

    override fun setSelection(items: Set<Path<Int>>) {
        selectionModel?.replaceAll(items.filter { visible(it) })

        scrollToSelection()
    }

    override fun removeSelection(items: Set<Path<Int>>) {
        selectionModel?.removeAll(items)
    }

    override fun clearSelection(): Unit = selectionModel?.clear().let {}

    override fun next(after: Path<Int>): Path<Int>? = rowFromPath(after)?.let { it + 1 }?.let { pathFromRow(it) }

    override fun previous(before: Path<Int>): Path<Int>? = rowFromPath(before)?.let { it - 1 }?.let { pathFromRow(it) }

    override fun visible(row: Int): Boolean = pathFromRow(row)?.let { visible(it) } ?: false

    override fun visible(path: Path<Int>): Boolean = when (path.depth) {
        0    -> rootVisible
        1    -> expanded(path.parent!!)
        else -> {
            val parent = path.parent

            when {
                parent == null || !expanded(parent) -> false
                else                                -> visible(parent)
            }
        }
    }

    public fun makeVisible(path: Path<Int>) {
        var parent = path.parent

        while (parent != null) {
            expand(parent)

            parent = parent.parent
        }
    }

    protected fun update(children: MutableList<View>, path: Path<Int>, index: Int? = rowFromPath(path)): Int? {
        var result = null as Int?

        if (index != null) {
            result = index
            if (index >= 0) {
                rowToPath[index] = path
            }

            // Path index not found (could be invisible)
            if (index in firstVisibleRow..lastVisibleRow) {
                generator?.let { rowGenerator ->
                    model[path].onSuccess { value ->
                        // pathToRow[path ] = index

                        val i = index % children.size

                        rowGenerator(this, value, path, index, children.getOrNull(i)).also { ui ->
                            children[i] = ui
                            layout(ui, value, path, index)

                            if (index + 1 < numRows - 1) {
                                ui.nextInAccessibleReadOrder = children[(index + 1) % children.size]
                            }
                        }

                        ++result
                    }
                }
            }
        }

        return result
    }

    protected fun layout(view: View, node: T, path: Path<Int>, index: Int) {
        rowPositioner?.let {
            view.bounds = it.rowBounds(this, node, path, index, view)

            minimumSize = Size(max(width, view.width), minHeight)
            idealSize   = Size(max(idealSize?.width ?: 0.0, view.idealSize?.width ?: minimumSize.width), minHeight)
        }
    }

    private fun updateNumRows() {
        numRows_ = rowsBelow(Path()) + if(rootVisible) 1 else 0
    }

    private fun findRow(at: Point, nearbyRow: Int) = min(numRows - 1, rowPositioner?.row(this, at) ?: nearbyRow)

    private fun siblingsAfter(path: Path<Int>, parent: Path<Int>) = path.bottom?.let {
        (it + 1 until numChildren(parent)).map { parent + it }
    } ?: emptyList()

    private fun ancestralSiblingsAfter(path: Path<Int>): Set<Path<Int>> {
        var parent = path.parent
        var child  = path
        val result = mutableSetOf<Path<Int>>()

        while (parent != null) {
            result += siblingsAfter(child, parent)
            child  = parent
            parent = parent.parent
        }

        result += siblingsAfter(child, parent ?: Path())

        return result
    }

    protected fun refreshAll() {
        val oldHeight = minHeight

        // FIXME: Move to better location; handle rootVisible case
        minHeight = getMinHeight()

        if (oldHeight == minHeight) {
            // FIXME: This reset logic could be handled better
            minVisiblePosition =  Origin
            maxVisiblePosition =  Origin
            firstVisibleRow    =  0
            lastVisibleRow     = -1
        }

        updateNumRows()

        handleDisplayRectEvent(Empty, displayRect)
    }

    private fun getMinHeight() = rowPositioner?.let {
        val root       = Path<Int>()
        val rootValue  = get(root).getOrNull()
        val rootHeight = if (rootVisible && rootValue != null) it.rowBounds(this, rootValue, root, 0).height else 0.0

        it.minimumSize(this, below = root).height + rootHeight
    } ?: 0.0 //heightBelow(root) + insets.run { top + bottom }

    private fun insertChildren(children: MutableList<View>, parent: Path<Int>, parentIndex: Int? = rowFromPath(parent)): Int? {
        var index: Int? = (parentIndex ?: -1) + 1

        (0 until numChildren(parent)).forEach {
            val old = index
            index   = insert(children, parent + it, index)

            if (old == index) {
                return@forEach
            }
        }

        return index
    }

    private fun insert(children: MutableList<View>, path: Path<Int>, index: Int? = rowFromPath(path)): Int? {
        if (index == null) {
            return null
        }

        // Path index not found (could be invisible)
        var result: Int? = index

        rowToPath[index] = path
        generator?.let {
            model[path].onSuccess { value ->
//                    pathToRow[path ] = index

                val expanded = expanded(path)

                if (children.size <= lastVisibleRow - firstVisibleRow) {
                    it(this, value, path, index).also {
                        when {
                            index > children.lastIndex -> children.add(it)
                            else                       -> children.add(index, it)
                        }

                        layout(it, value, path, index)
                    }
                } else {
                    update(children, path, index)
                }

                result = result?.let { it + 1 }

                if (expanded) {
                    result = insertChildren(children, path, index)
                }
            }
        }

        return result
    }

    private fun updateChildren(children: MutableList<View>, parent: Path<Int>, parentIndex: Int? = rowFromPath(parent)): Int? {
        var index: Int? = (parentIndex ?: -1) + 1

        (0 until numChildren(parent)).forEach {
            val old = index
            index   = updateRecursively(children, parent + it, index)

            if (old == index) {
                return@forEach
            }
        }

        return index
    }

    private fun updateRecursively(children: MutableList<View>, path: Path<Int>, index: Int? = rowFromPath(path)): Int? {
        var result = update(children, path, index)

        if (result != null && expanded(path)) {
            result = updateChildren(children, path, index)
        }

        return result
    }

    private fun rowExpanded(index: Int) = pathFromRow(index)?.let { expanded(it) } ?: false

    override fun pathFromRow(index: Int): Path<Int>? = when {
        model.isEmpty() || (index < 0 && !rootVisible) -> null
        else                                           -> {
            rowToPath.getOrElse(index) {
                addRowsToPath(Path(), index + if (!rootVisible) 1 else 0)?.first?.also {
                    rowToPath[index] = it
                }
            }
        }
    }

    override fun rowFromPath(path: Path<Int>): Int? /*= pathToRow.getOrPut(path)*/ {
        var row         = if (rootVisible) 0 else -1
        var pathIndex   = 0
        var currentPath = Path<Int>()
        var numChildren = numChildren(currentPath)

        if (pathIndex < path.depth) {
            var i = 0
            while (i < numChildren) {
                ++row

                if (i == path[pathIndex]) {
                    pathIndex++

                    if (pathIndex >= path.depth) {
                        return row
                    }

                    if (!rowExpanded(row)) {
                        break
                    }

                    currentPath += i
                    numChildren = numChildren(currentPath)
                    i = -1
                } else {
                    row += rowsBelow(currentPath + i)
                }
                ++i
            }

            row = -1
        }

        return row
    }

    internal fun rowsBelow(path: Path<Int>): Int {
        var numRows = 0

        if (expanded(path) && (path.depth == 0 || visible(path))) {
            val numChildren = numChildren(path)

            (0 until numChildren).asSequence().map { path + it }.forEach { numRows += rowsBelow(it) + 1 }
        }

        return numRows
    }

    private fun expandAllBelowPath(path: Path<Int>, expandedPath: MutableSet<Path<Int>> = mutableSetOf()) {
        if (model.isLeaf(path)) {
            return
        }

        val numChildren = numChildren(path)

        (0 until numChildren).forEach {
            (path + it).let { child ->
                expandedPath += child

                if (!model.isLeaf(child)) {
                    if (!expanded(child)) {
                        expandedPath.add(child)
                    }

                    expandAllBelowPath(child, expandedPath)
                }
            }
        }
    }

    private fun addRowsToPath(path: Path<Int>, index: Int): Pair<Path<Int>, Int>? {
        if (index <= 0) {
            return path to index
        }

        var newIndex = index

        var newPath     = path
        val numChildren = numChildren(path)

        for(i in 0 until numChildren) {
            newPath = path + i

            --newIndex

            if (newIndex == 0) {
                break
            }

            if (expanded(newPath)) {
                addRowsToPath(newPath, newIndex)?.also {
                    newPath  = it.first
                    newIndex = it.second
                }

                if (newIndex == 0) {
                    break
                }
            }
        }

        return if (newIndex == 0) newPath to newIndex else null
    }

    public fun scrollTo(path: Path<Int>) {
        val index = rowFromPath(path)

        if (index != null) {
            this[path].onSuccess { item ->
                rowPositioner?.rowBounds(this, item, path, index)?.let {
                    scrollTo(it)
                }
            }
        }
    }

    public fun scrollToSelection() {
        lastSelection?.let { scrollTo(it) }
    }
}

private class ExpansionObserversImpl<T>(private val source: Tree<T, *>, mutableSet: MutableSet<ExpansionObserver<T>> = mutableSetOf()): SetPool<ExpansionObserver<T>>(mutableSet) {
    operator fun invoke(paths: Set<Path<Int>>) = delegate.forEach { it(source, paths) }
}

private object PathComparator: Comparator<Path<Int>> {
    override fun compare(a: Path<Int>, b: Path<Int>): Int {
        (0 until min(a.depth, b.depth)).forEach {
            (a[it] - b[it]).let {
                if (it != 0) {
                    return it
                }
            }
        }

        return 0
    }
}

private val DepthComparator = Comparator<Path<Int>> { a, b -> b.depth - a.depth }
