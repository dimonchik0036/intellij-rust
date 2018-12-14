/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.dfa

import com.intellij.util.ThreeState
import com.intellij.util.containers.Queue
import org.jetbrains.coverage.gnu.trove.TIntObjectHashMap
import org.rust.ide.utils.skipParenExprDown
import org.rust.ide.utils.skipParenExprUp
import org.rust.lang.core.cfg.CFGNode
import org.rust.lang.core.cfg.CFGNodeData
import org.rust.lang.core.cfg.ControlFlowGraph.Companion.buildFor
import org.rust.lang.core.dfa.value.DfaConstValue
import org.rust.lang.core.dfa.value.DfaUnknownValue
import org.rust.lang.core.dfa.value.DfaValue
import org.rust.lang.core.dfa.value.DfaValueFactory
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.types.type

class DataFlowRunner(val function: RsFunction) {
    private val valueFactory: DfaValueFactory = DfaValueFactory()
    private val instructions = hashSetOf<BinOpInstruction>()
    private val states = TIntObjectHashMap<DfaMemoryState>()
    //    var unvisitedElements: Set<RsElement> = emptySet()
//        private set
    //for debug
    var resultState: DfaMemoryState = createMemoryState()
        private set

    init {
        initFunctionParameters()
    }

    val constantConditionalExpression
        get(): DfaResult {
            val trueSet = hashSetOf<RsExpr>()
            val falseSet = hashSetOf<RsExpr>()
            instructions.asSequence()
                .filter { element -> instructions.all { other -> element.anchor == other.anchor || element.anchor !in other.anchor } }
                .forEach {
                    if (!it.isConst) {
                        val reachable = it.reachable
                        if (reachable.isTrueReachable && !reachable.isFalseReachable) {
                            trueSet += it.anchor
                        } else if (reachable.isFalseReachable && !reachable.isTrueReachable) {
                            falseSet += it.anchor
                        }
                    }
                }
            return DfaResult(trueSet, falseSet)
        }

    private fun createMemoryState(): DfaMemoryState = DfaMemoryState.EMPTY

    fun analyze(): RunnerResult = try {
        val block = function.block
        if (block != null) {
            val visitor = NodeVisitorState(block)
            lineVisit(visitor)
//            unvisitedElements = visitor.unvisitedElements.filter { it !is RsBlock }.toSet()
        }
        RunnerResult.OK
    } catch (e: Exception) {
        RunnerResult.NOT_APPLICABLE
    }

    private fun initFunctionParameters() {
        var state = createMemoryState()
        function.valueParameterList?.valueParameterList?.forEach {
            val element = it.pat as? RsPatIdent
            val binPat = element?.patBinding
            if (binPat != null) {
                state = state.plus(binPat, valueFactory.createTypeValue(binPat.type))
            }
        }
        setState(0, state)
    }

    /***
     * @param endIndex 1 is index of [org.rust.lang.core.cfg.CFGNodeData.Exit] node
     */
    private fun lineVisit(visitorState: NodeVisitorState, endIndex: Int = 1) {
        while (true) {
            val node = visitorState.nextNode()
            if (node == null || node.index == endIndex) return
            with(node.data) {
                when {
                    this is CFGNodeData.AST && this.element != null -> visitAstNode(this.element, node, visitorState)
                    this is CFGNodeData.Dummy -> visitDummyNode(node, visitorState)
                    else -> updateNextState(node, visitorState)
                }
            }
        }
    }

    private fun updateNextState(node: CFGNode, visitorState: NodeVisitorState, nextNode: CFGNode? = node.nextNode) {
        if (nextNode == null) return
        visitorState.addNode(nextNode)
        mergeStates(node, nextNode)
    }

    private fun getStateWithCheck(index: Int): DfaMemoryState = states[index] ?: error("Index $index")
    private fun setState(index: Int, state: DfaMemoryState) {
        states.put(index, state)
        resultState = state
    }

    private fun mergeStates(nodeFrom: CFGNode, nodeTo: CFGNode) {
        val currentState = states[nodeFrom.index] ?: return
        mergeState(currentState, nodeTo)
    }

    private fun mergeState(state: DfaMemoryState, nodeTo: CFGNode) {
        val stateFromNextNode = states[nodeTo.index]
        val nextState = if (stateFromNextNode != null) state.unite(stateFromNextNode) else state
        setState(nodeTo.index, nextState)
    }

    private fun visitAstNode(element: RsElement, node: CFGNode, visitorState: NodeVisitorState) = when (element) {
        is RsLetDecl -> visitLetDeclNode(node, element, visitorState)
        is RsBinaryExpr -> visitBinExpr(node, element, visitorState)
        is RsRetExpr -> {
        }
        is RsExpr -> tryVisitControlFlow(element, node, visitorState)
        else -> updateNextState(node, visitorState)
    }

    private fun visitBinExpr(node: CFGNode, expr: RsBinaryExpr, visitorState: NodeVisitorState) {
        val state = getStateWithCheck(node.index)
        when (expr.operatorType) {
            is AssignmentOp -> visitAssignmentBinOp(expr, state, node, visitorState)
            is BoolOp -> tryVisitControlFlow(expr, node, visitorState)
            else -> updateNextState(node, visitorState)
        }
    }

    private fun tryVisitControlFlow(expr: RsExpr, node: CFGNode, visitorState: NodeVisitorState) {
        val parent = expr.skipParenExprUp().parent
        when (parent) {
            is RsCondition -> visitCondition(expr, parent, node, visitorState)
            is RsMatchExpr -> visitMatchExpr(expr, parent, node, visitorState)
            is RsTryExpr -> visitTryExpr(expr, parent, node, visitorState)
            else -> updateNextState(node, visitorState)
        }
    }

    private fun visitTryExpr(expr: RsExpr, tryExpr: RsTryExpr, node: CFGNode, visitorState: NodeVisitorState) {
        // TODO ? now do nothing
        updateNextState(node, visitorState, node.firstOutNode)
    }

    private fun visitCondition(expr: RsExpr, condition: RsCondition, node: CFGNode, visitorState: NodeVisitorState) {
        val parent = condition.parent
        when (parent) {
            is RsIfExpr -> visitIfExpr(expr, parent, node, visitorState)
            else -> updateNextState(node, visitorState)
        }
    }

    private fun visitIfExpr(expr: RsExpr, ifExpr: RsIfExpr, node: CFGNode, visitorState: NodeVisitorState) {
        val ifNode = visitorState.getNodeFromElement(ifExpr)
        val state = getStateWithCheck(node.index)
        val (trueBranch, falseBranch) = node.firstControlFlowSplit ?: error("Couldn't find control flow split")
        val value = tryEvaluateExpr(expr, state)

        when (value.threeState) {
            ThreeState.YES -> updateNextState(node, visitorState, trueBranch)
            ThreeState.NO -> updateNextState(node, visitorState, falseBranch)
            ThreeState.UNSURE -> {
                visitBranch(trueBranch, state.intersect(value.trueState), visitorState, ifNode.index + 1)
                visitBranch(falseBranch, state.intersect(value.falseState), visitorState, ifNode.index + 1)
                updateNextState(ifNode, visitorState)
            }
        }
    }

    private fun visitMatchExpr(expr: RsExpr, matchExpr: RsMatchExpr, node: CFGNode, visitorState: NodeVisitorState) {
        val matchNode = visitorState.getNodeFromElement(matchExpr)
        val state = getStateWithCheck(node.index)
        val nodes = node.outgoingNodes
        nodes.forEach { visitBranch(it, state, visitorState, matchNode.index) }
        updateNextState(matchNode, visitorState)
    }

    private fun visitBranch(node: CFGNode, state: DfaMemoryState, visitorState: NodeVisitorState, endIndex: Int) {
        setState(node.index, state)
        visitorState.addNode(node)
        lineVisit(visitorState, endIndex)
    }

    private fun tryEvaluateExpr(expr: RsExpr?, state: DfaMemoryState): DfaCondition {
        val expr = expr?.skipParenExprDown() ?: return DfaCondition.UNSURE
        return when (expr) {
            is RsLitExpr -> tryEvaluateLitExpr(expr)
            is RsUnaryExpr -> tryEvaluateUnaryExpr(expr, state)
            is RsBinaryExpr -> tryEvaluateBinExpr(expr, state)
            is RsPathExpr -> tryEvaluatePathExpr(expr, state)
            else -> DfaCondition.UNSURE
        }.addBinOpIfSure(expr)
    }

    private fun DfaCondition.addBinOpIfSure(expr: RsExpr): DfaCondition {
        if (sure) instructions += BinOpInstruction(DfaReachableBranch.fromThreeState(threeState), expr)
        return this
    }

    private fun tryEvaluatePathExpr(expr: RsPathExpr, state: DfaMemoryState): DfaCondition {
        val constValue = valueFromPathExpr(expr, state) as? DfaConstValue ?: return DfaCondition.UNSURE
        return DfaCondition(fromBool(constValue.value as? Boolean))
    }

    private fun tryEvaluateLitExpr(expr: RsLitExpr): DfaCondition = DfaCondition(fromBool((expr.kind as? RsLiteralKind.Boolean)?.value))

    private fun tryEvaluateUnaryExpr(expr: RsUnaryExpr, state: DfaMemoryState): DfaCondition {
        if (expr.excl == null) return DfaCondition.UNSURE
        val result = tryEvaluateExpr(expr.expr, state)
        return DfaCondition(result.threeState.not, trueState = result.falseState, falseState = result.trueState)
    }

    private fun tryEvaluateBinExpr(expr: RsBinaryExpr, state: DfaMemoryState): DfaCondition {
        val op = expr.operatorType
        return when (op) {
            is LogicOp -> tryEvaluateBinExprWithLogicOp(op, expr, state)
            // TODO add separately EqualityOp
            is BoolOp -> tryEvaluateBinExprWithRange(op, expr, state)
            else -> DfaCondition.UNSURE
        }
    }

    private fun tryEvaluateBinExprWithLogicOp(op: LogicOp, expr: RsBinaryExpr, state: DfaMemoryState): DfaCondition {
        val left = expr.left.skipParenExprDown()
        val right = expr.right?.skipParenExprDown() ?: return DfaCondition.UNSURE
        val leftResult = tryEvaluateExpr(left, state)
        return when (op) {
            LogicOp.OR -> if (leftResult.threeState == ThreeState.YES) leftResult else leftResult.or(tryEvaluateExpr(right, state))
            LogicOp.AND -> if (leftResult.threeState == ThreeState.NO) leftResult else leftResult.and(tryEvaluateExpr(right, state.intersect(leftResult.trueState)))
        }
    }

    private fun tryEvaluateConst(op: BoolOp, leftExpr: RsExpr?, leftValue: DfaValue, rightExpr: RsExpr?, rightValue: DfaValue): DfaCondition? = when {
        leftExpr == null || rightExpr == null -> DfaCondition.UNSURE
        op !is EqualityOp -> null
        leftValue is DfaUnknownValue && rightValue is DfaUnknownValue -> if (equalVariable(leftExpr, rightExpr)) DfaCondition(ThreeState.fromBoolean(op is EqualityOp.EQ)) else DfaCondition.UNSURE
        leftValue is DfaConstValue && rightValue is DfaConstValue -> DfaCondition(ThreeState.fromBoolean(if (op is EqualityOp.EQ) leftValue == rightValue else leftValue != rightValue))
        leftValue is DfaConstValue && rightValue is DfaUnknownValue -> valueFromConstantAndUnknown(leftValue, op, rightExpr)
        leftValue is DfaUnknownValue && rightValue is DfaConstValue -> valueFromConstantAndUnknown(rightValue, op, leftExpr)
        else -> null
    }

    private fun equalVariable(leftExpr: RsExpr?, rightExpr: RsExpr?): Boolean {
        val leftVariable = leftExpr?.toVariable() ?: return false
        val rightVariable = rightExpr?.toVariable() ?: return false
        return leftVariable == rightVariable
    }

    private fun valueFromConstantAndUnknown(constValue: DfaConstValue, op: EqualityOp, otherExpr: RsExpr): DfaCondition? {
        val boolValue = constValue.value as? Boolean ?: return null
        val otherVariable = otherExpr.toVariable()

        val constValue = valueFactory.createBoolValue(boolValue)
        val resultValue = constValue.let { if (op is EqualityOp.EQ) it else it.negated }
        val trueState = createMemoryState().uniteValue(otherVariable, resultValue)
        val falseState = createMemoryState().uniteValue(otherVariable, resultValue.negated)
        return DfaCondition(ThreeState.UNSURE, trueState, falseState)
    }

    private fun DfaMemoryState.uniteRangeIfNotEmpty(variable: Variable?, range: LongRangeSet): DfaMemoryState = if (!range.isEmpty) uniteValue(variable, valueFactory.createRange(range)) else this

    private fun tryEvaluateBinExprWithRange(op: BoolOp, expr: RsBinaryExpr, state: DfaMemoryState): DfaCondition {
        val leftValue = valueFromExpr(expr.left, state)
        val rightValue = valueFromExpr(expr.right, state)

        val value = tryEvaluateConst(op, expr.left, leftValue, expr.right, rightValue)
        if (value != null) return value

        if (leftValue.type != rightValue.type) return DfaCondition.UNSURE
        val leftRange = LongRangeSet.fromDfaValue(leftValue) ?: return DfaCondition.UNSURE
        val rightRange = LongRangeSet.fromDfaValue(rightValue) ?: return DfaCondition.UNSURE

        val leftVariable = expr.left.toVariable()
        val rightVariable = expr.right?.toVariable()
        val (leftTrueResult, rightTrueResult) = leftRange.compare(op, rightRange)
        val trueState = createMemoryState()
            .uniteRangeIfNotEmpty(leftVariable, leftTrueResult)
            .uniteRangeIfNotEmpty(rightVariable, rightTrueResult)

        val (leftFalseResult, rightFalseResult) = leftRange.compare(op.not, rightRange)
        val falseState = createMemoryState()
            .uniteRangeIfNotEmpty(leftVariable, leftFalseResult)
            .uniteRangeIfNotEmpty(rightVariable, rightFalseResult)

        return when {
            leftTrueResult.isEmpty && rightTrueResult.isEmpty -> DfaCondition(ThreeState.NO, trueState = trueState, falseState = falseState)
            leftRange in leftTrueResult && rightRange in rightTrueResult -> DfaCondition(ThreeState.YES, trueState = trueState, falseState = falseState)
            else -> DfaCondition(ThreeState.UNSURE, trueState = trueState, falseState = falseState)
        }
    }

    private fun visitLetDeclNode(node: CFGNode, element: RsLetDecl, visitorState: NodeVisitorState) {
        val pat = element.pat
        when (pat) {
            is RsPatIdent -> {
                val state = getStateWithCheck(node.index)
                val bin = pat.patBinding
                val expr = element.expr
                // TODO add type check
                val value = if (expr != null) valueFromExpr(expr, state) else valueFactory.createTypeValue(bin.type)
                setState(node.index, state.plus(bin, value))
            }
            is RsPatTup -> {
                var state = getStateWithCheck(node.index)
                val values = valuesFromTuple(element.expr, state)
                pat.patList.forEachIndexed { index, it ->
                    state = state.plus((it as? RsPatIdent)?.patBinding, values.getOrElse(index) { DfaUnknownValue })
                }
                setState(node.index, state)
            }
        }
        updateNextState(node, visitorState)
    }

    private fun valuesFromTuple(element: RsExpr?, state: DfaMemoryState): List<DfaValue> {
        val tuple = element as? RsTupleExpr ?: return emptyList()
        return tuple.exprList.map { valueFromExpr(it, state) }
    }

    private fun valueFromExpr(expr: RsExpr?, state: DfaMemoryState): DfaValue {
        val expr = expr?.skipParenExprDown() ?: return DfaUnknownValue
        return when (expr) {
            is RsPathExpr -> valueFromPathExpr(expr, state)
            is RsLitExpr -> valueFactory.createLiteralValue(expr)
            is RsBinaryExpr -> valueFromBinExpr(expr, state)
            is RsUnaryExpr -> valueFromUnaryExpr(expr, state)
            else -> valueFactory.createTypeValue(expr.type)
        }
    }

    private fun valueFromUnaryExpr(expr: RsUnaryExpr, state: DfaMemoryState): DfaValue =
        when {
            expr.excl != null -> valueFromExpr(expr.expr, state).negated
            expr.minus != null -> valueFromExpr(expr.expr, state).minus
            else -> DfaUnknownValue
        }

    private fun valueFromPathExpr(expr: RsPathExpr, state: DfaMemoryState): DfaValue {
        val variable = expr.toVariable() ?: return DfaUnknownValue
        return state.getOrUnknown(variable)
    }

    private fun valueFromBinExpr(expr: RsBinaryExpr, state: DfaMemoryState): DfaValue {
        val op = expr.operatorType
        return when (op) {
            is AssignmentOp -> DfaUnknownValue
            else -> valueFromBinOp(op, expr, state)
        }
    }

    private fun valueFromBinOp(op: BinaryOperator, expr: RsBinaryExpr, state: DfaMemoryState): DfaValue {
        val leftValue = valueFromExpr(expr.left, state)
        val rightValue = valueFromExpr(expr.right, state)
        val value = valueFromConstValue(op, expr.left, leftValue, expr.right, rightValue)
        if (value != null) {
            return value
        }

        if (leftValue.type != rightValue.type) return DfaUnknownValue
        val leftRange = LongRangeSet.fromDfaValue(leftValue) ?: return DfaUnknownValue
        val rightRange = LongRangeSet.fromDfaValue(rightValue) ?: return DfaUnknownValue
        return valueFactory.createRange(leftRange.binOpFromToken(op, rightRange))
    }

    private fun evaluateBoolExpr(op: BoolOp, left: Boolean, right: Boolean): Boolean = when (op) {
        is EqualityOp -> if (op is EqualityOp.EQ) left == right else left != right
        is LogicOp -> if (op is LogicOp.OR) left || right else left && right
        else -> error("Illegal operation `$op` for boolean")
    }

    private fun valueFromConstValue(op: BinaryOperator, leftExpr: RsExpr?, leftValue: DfaValue, rightExpr: RsExpr?, rightValue: DfaValue): DfaValue? = when {
        leftExpr == null || rightExpr == null -> DfaUnknownValue
        op !is BoolOp -> null
        leftValue is DfaConstValue && rightValue is DfaConstValue -> valueFactory.createBoolValue(evaluateBoolExpr(op, leftValue.value as Boolean, rightValue.value as Boolean))
        leftValue is DfaUnknownValue && rightValue is DfaUnknownValue -> if (equalVariable(leftExpr, rightExpr)) valueFactory.createBoolValue(op is EqualityOp.EQ) else DfaUnknownValue
        leftValue is DfaUnknownValue || rightValue is DfaUnknownValue || leftValue is DfaConstValue || rightValue is DfaConstValue -> DfaUnknownValue
        else -> null
    }

    private fun visitAssignmentBinOp(expr: RsBinaryExpr, state: DfaMemoryState, node: CFGNode, visitorState: NodeVisitorState) {
        val variable = variable(expr.left, state)
        if (variable != null) {
            val value = valueFromExpr(expr.right, state)
            val nextNode = node.nextNode ?: return
            visitorState.addNode(nextNode)
            mergeState(state.plus(variable, value), nextNode)
        } else {
            updateNextState(node, visitorState)
        }
    }

    private fun variable(expr: RsExpr, state: DfaMemoryState): Variable? {
        // TODO ? check variable !in state
//        if (variable == null || variable !in state) error("Couldn't find variable '${variable?.text}'")
        return expr.toVariable()
    }

    private fun visitDummyNode(node: CFGNode, visitorState: NodeVisitorState) {
        val nextNode = visitorState.getNode(node.index + 1)
        val expr = nextNode.data.element
        when (expr) {
            is RsLoopExpr -> visitLoop(expr, node, nextNode, visitorState)
            is RsWhileExpr -> visitWhile(expr, node, nextNode, visitorState)
            is RsForExpr -> visitFor(expr, node, nextNode, visitorState)
            else -> updateNextState(node, visitorState)
        }
    }

    private fun visitLoop(expr: RsLoopExpr, dummyNode: CFGNode, loopNode: CFGNode, visitorState: NodeVisitorState) {
        updateNextState(dummyNode, visitorState)
        lineVisit(visitorState, dummyNode.index)
    }

    private fun visitWhile(whileExpr: RsWhileExpr, dummyNode: CFGNode, whileNode: CFGNode, visitorState: NodeVisitorState) {
        updateNextState(dummyNode, visitorState)
        lineVisit(visitorState, whileNode.index)

        val expr = whileExpr.condition?.expr
        if (expr != null) {
            val state = getStateWithCheck(whileNode.index)
            val value = tryEvaluateExpr(expr, state)
        }
        // TODO: check expr and visit block
        updateNextState(whileNode, visitorState)
    }

    //TODO: check expr return
    private fun visitFor(expr: RsForExpr, dummyNode: CFGNode, forNode: CFGNode, visitorState: NodeVisitorState) {
        updateNextState(dummyNode, visitorState)
        lineVisit(visitorState, forNode.index)
        // TODO: check expr and visit block
        updateNextState(forNode, visitorState)
    }
}

private class NodeVisitorState(block: RsBlock) {
    //    private val visited = hashSetOf<CFGNode>()
    private val cfg = buildFor(block)
    private val table = cfg.buildLocalIndex()
    private val queue = Queue<CFGNode>(2)
//    val unvisitedElements: Set<RsElement> get() = table.entries.filter { it.value.firstOrNull { node -> node in visited } == null }.map { it.key }.toSet()

    init {
        queue.addLast(cfg.entry)
    }

    fun nextNode(): CFGNode? {
        if (queue.isEmpty) return null
        val node = queue.pullFirst()
//        visited += node
        return node
    }

    fun addNode(node: CFGNode): Unit = queue.addLast(node)

    fun getNode(index: Int): CFGNode = cfg.graph.getNode(index)
    fun getNodeFromElement(element: RsElement): CFGNode = table[element]?.firstOrNull()
        ?: error("couldn't find node for '${element.text}'")
}

val CFGNode.outgoingNodes: Sequence<CFGNode> get() = generateSequence(this.firstOutEdge) { it.nextSourceEdge }.map { it.target }
val CFGNode.nextNode: CFGNode? get() = this.outgoingNodes.lastOrNull()
val CFGNode.firstControlFlowSplit: Pair<CFGNode, CFGNode>?
    get() {
        var out = this.outgoingNodes
        while (out.count() != 2) {
            out = out.firstOrNull()?.outgoingNodes ?: emptySequence()
            if (out.none()) return null
        }
        return out.elementAt(1) to out.elementAt(0)
    }
val CFGNode.hasSingleOut: Boolean get() = this.outgoingNodes.singleOrNull() != null
val CFGNode.firstOutNode: CFGNode? get() = this.firstOutEdge?.target
val CFGNode.firstInNode: CFGNode? get() = this.firstInEdge?.source

private fun RsExpr.toVariable(): Variable? {
    val expr = skipParenExprDown()
    return if (expr is RsPathExpr) expr.path.reference.resolve() as? Variable else null
}

private operator fun RsElement.contains(other: RsElement): Boolean = other.textRange in this.textRange

data class DataFlowAnalysisResult(val result: RunnerResult, val runner: DataFlowRunner)