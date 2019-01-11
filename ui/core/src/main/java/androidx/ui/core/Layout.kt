/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.ui.core

import com.google.r4a.Ambient
import com.google.r4a.Children
import com.google.r4a.Component
import com.google.r4a.Composable
import com.google.r4a.R4a

/**
 * A part of the composition that can be measured. This represents a [MeasureBox] somewhere
 * down the hierarchy.
 *
 * @return a [Placeable] that can be used within a [MeasureOperations.layout] block
 */
// TODO(mount): Make this an inline class when private constructors are possible
class Measurable internal constructor(private val measureBox: MeasureBox) {
    fun measure(constraints: Constraints): Placeable {
        measureBox.measure(constraints)
        return Placeable(measureBox)
    }
}

/**
 * A measured [MeasureBox] from [Measurable.measure]. The [place] call can be made within
 * [MeasureOperations.layout]. In the future, this will only be accessible within the [layout]
 * call.
 */
// TODO(mount): Make this an inline class when private constructors are possible
class Placeable internal constructor(private val measureBox: MeasureBox) {
    val width = measureBox.layoutNode.size.width
    val height = measureBox.layoutNode.size.height

    fun place(x: Dimension, y: Dimension) {
        measureBox.moveTo(x, y)
        measureBox.layout()
    }
}

/**
 * MeasureBox is a tag that can be used to measure and position zero or more children.
 * The MeasureBox accepts a [Constraints] argument to use to determine its measurements.
 * For example, to get several children to draw on top of each other:
 *     @Composable fun Stack(@Children children: () -> Unit) {
 *         <MeasureBox> constraints ->
 *             val measurables = collect(children)
 *             val placeables = measurables.map { it.measure(constraints) }
 *             val width = placeables.maxBy { it.width }
 *             val height = placeables.maxBy { it.height }
 *             layout(width, height) {
 *                 placeables.forEach { placeable ->
 *                     placeable.place((width - placeable.width) / 2,
 *                                     (height - placeable.height) / 2))
 *                 }
 *             }
 *         </MeasureBox>
 *     }
 *
 * Until receiver scopes work, [MeasureOperations] is passed as an extra argument.
 */
class MeasureBox(@Children(composable = false) var block: (constraints: Constraints, measureOperations: MeasureOperations) -> Unit) :
    Component() {
    internal var layoutBlock: () -> Unit = {}
    private val ref = Ref<LayoutNode>()
    internal val layoutNode: LayoutNode get() = ref.value!!
    internal var ambients: Ambient.Reference? = null

    /**
     * We should keep the list of all the children as later when we will need to remeasure them
     * during the Android View onMeasure call. It will not be a composition pass so we will not
     * be able to go through the composition tree and recollect.
     * It currently doesn't work well with dynamic inserts and removes of the children as we can't
     * detect it and have to wait for R4a effects for this.
     * Having effects later in onDispose we would remove the child from this list which means
     * it is detached. Later we will also need to figure out how to detect reordering of the
     * children as here we kind of keeping our own tree of widgets only of MeasureBox type.
     */
    internal val children = mutableListOf<MeasureBox>()

    /**
     * During the first compose we have to add this MeasureBox into a parent children list.
     * That is how we attach the child into the parent.
     * We could later reimplemented it with use of R4a effects like didCommit.
     */
    private var firstPass = true

    override fun compose() {
        <ParentsChildren.Consumer> parentsChildren ->
            if (firstPass) {
                // save this as a parent's child
                parentsChildren.add(this)
                firstPass = false
            }
            <Ambient.Portal> reference ->
                ambients = reference
                <LayoutNode ref measureBox=this />
            </Ambient.Portal>
            if (recomposeMeasureBox == null) {
                recomposeMeasureBox = this
                measure(layoutNode.constraints)
                recomposeMeasureBox = null
                layout()
            }
        </ParentsChildren.Consumer>
    }

    internal fun measure(constraints: Constraints) {
        layoutNode.constraints = constraints
        val measureOperations = MeasureOperations(this)
        block(constraints, measureOperations)
    }

    internal fun layout() {
        layoutNode.visible = true
        layoutBlock()
    }

    internal fun moveTo(x: Dimension, y: Dimension) {
        layoutNode.moveTo(x, y)
    }

    internal fun resize(width: Dimension, height: Dimension) {
        layoutNode.resize(width, height)
    }
}

internal val ParentsChildren = Ambient.of<MutableList<MeasureBox>>()

internal var recomposeMeasureBox: MeasureBox? = null

/**
 * Receiver scope for [MeasureBox]'s child lambda to add [collect] and [layout] functions.
 */
class MeasureOperations internal constructor(private val measureBox: MeasureBox) {
    /**
     * Compose [children] into the [MeasureBox] and return a list of [Measurable]s within
     * the children. Composition stops at [MeasureBox] children. Further composition requires
     * calling [Measurable.measure].
     */
    fun collect(@Children children: () -> Unit): List<Measurable> {
        val layoutNode = measureBox.layoutNode

        val ambients = measureBox.ambients!!
        R4a.composeInto(layoutNode, ambients.getAmbient(ContextAmbient), ambients) {
            <ParentsChildren.Provider value=measureBox.children>
                <children />
            </ParentsChildren.Provider>
        }
        measureBox.children.forEach { measureBox ->
            measureBox.layoutNode.visible = false
        }
        return measureBox.children.map { measureBox ->
            Measurable(measureBox)
        }
    }

    /**
     * Set the size of the [MeasureBox] to [width] x [height] and set the [block] used to
     * position children with [Placeable.place]. It is possible to call [Measurable.measure]
     * inside [block] for any [Measurables] that aren't needed to calculate [MeasureBox]'s size.
     */
    fun layout(width: Dimension, height: Dimension, block: () -> Unit) {
        measureBox.resize(width, height)
        measureBox.layoutBlock = block
    }
}

/**
 * Temporary needed to be able to use the component from the adapter module. b/120971484
 */
@Composable
fun MeasureBoxComposable(@Children(composable = false) block: (constraints: Constraints, measureOperations: MeasureOperations) -> Unit) {
    <MeasureBox block=block/>
}