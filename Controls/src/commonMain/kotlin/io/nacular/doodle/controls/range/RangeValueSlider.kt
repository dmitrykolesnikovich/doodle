package io.nacular.doodle.controls.range

import io.nacular.doodle.controls.BasicConfinedRangeModel
import io.nacular.doodle.controls.ConfinedRangeModel
import io.nacular.doodle.core.View
import io.nacular.doodle.utils.observable
import kotlin.math.max
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.reflect.KClass

public abstract class RangeValueSlider<T> internal constructor(
                    model: ConfinedRangeModel<T>,
        private val type : KClass<T>): View() where T: Number, T: Comparable<T> {

    public constructor(range: ClosedRange<T>, value: ClosedRange<T> = range.start .. range.start, type: KClass<T>): this(BasicConfinedRangeModel(range, value) as ConfinedRangeModel<T>, type)

    public var snapToTicks: Boolean by observable(false) { _,new ->
        if (new) {
            value = value // update value to ensure snapped to the closest tick
        }

        ticksChanged()
    }

    public var ticks: Int = 0
        set(new) {
            field = max(0, new)

            snapSize = if (field > 1) range.size.toDouble() / (field - 1) else null
        }

    public var snapSize: Double? = null
        private set(new) {
            if (new == field) return

            field = new

            if (snapToTicks) {
                value = value // update value to ensure snapped to the closest tick
            }

            ticksChanged()
        }

    public var model: ConfinedRangeModel<T> = model
        set(new) {
            field.rangeChanged -= modelChanged

            field = new.also {
                it.rangeChanged += modelChanged
            }
        }

    public var value: ClosedRange<T>
        get(   ) = model.range
        set(new) {
            val snapSize_ = snapSize

            val s = if (snapToTicks && snapSize_ != null) cast((round(range.start.toDouble() + (new.start.toDouble       () - range.start.toDouble()) / snapSize_) * snapSize_)) else new.start
            val e = if (snapToTicks && snapSize_ != null) cast((round(range.start.toDouble() + (new.endInclusive.toDouble() - range.start.toDouble()) / snapSize_) * snapSize_)) else new.endInclusive

            model.range = s..e
        }

    public var range: ClosedRange<T>
        get(   ) = model.limits
        set(new) { model.limits = new }

    internal fun set(to: ClosedRange<Double>) {
        value = cast(to.start) .. cast(to.endInclusive)
    }

    internal fun adjust(startBy: Double, endBy: Double) {
        value = cast(value.start.toDouble() + startBy) .. cast(value.endInclusive.toDouble() + endBy)
    }

    internal fun setLimits(range: ClosedRange<Double>) {
        model.limits = cast(range.start) .. cast(range.endInclusive)
    }

    protected abstract fun changed      (old: ClosedRange<T>, new: ClosedRange<T>)
    protected abstract fun limitsChanged(old: ClosedRange<T>, new: ClosedRange<T>)
    protected abstract fun ticksChanged ()

    private val modelChanged: (ConfinedRangeModel<T>, ClosedRange<T>, ClosedRange<T>) -> Unit = { _,old,new ->
        changed(old, new)
    }

    private val limitsChanged: (ConfinedRangeModel<T>, ClosedRange<T>, ClosedRange<T>) -> Unit = { _,old,new ->
        limitsChanged(old, new)
    }

    private fun cast(value: Double): T {
        return when (type) {
            Int::class    -> value.roundToInt           () as T
            Float::class  -> value.toFloat              () as T
            Double::class -> value                         as T
            Long::class   -> value.roundToLong          () as T
            Char::class   -> value.roundToInt().toChar  () as T
            Short::class  -> value.roundToInt().toShort () as T
            Byte::class   -> value.roundToInt().toByte  () as T
            else          -> value                         as T
        }
    }

    init {
        model.rangeChanged  += modelChanged
        model.limitsChanged += limitsChanged
    }
}