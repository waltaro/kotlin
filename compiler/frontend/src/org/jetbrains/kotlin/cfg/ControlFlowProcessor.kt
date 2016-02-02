/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.cfg

import com.google.common.collect.Lists
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.SmartFMap
import com.intellij.util.containers.ContainerUtil
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.cfg.pseudocode.ControlFlowInstructionsGenerator
import org.jetbrains.kotlin.cfg.pseudocode.PseudoValue
import org.jetbrains.kotlin.cfg.pseudocode.Pseudocode
import org.jetbrains.kotlin.cfg.pseudocode.PseudocodeImpl
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.AccessTarget
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.InstructionWithValue
import org.jetbrains.kotlin.cfg.pseudocode.instructions.eval.MagicKind
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.AnonymousFunctionDescriptor
import org.jetbrains.kotlin.lexer.KtToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.CompileTimeConstantUtils
import org.jetbrains.kotlin.resolve.calls.callUtil.*
import org.jetbrains.kotlin.resolve.calls.model.*
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.TransientReceiver
import org.jetbrains.kotlin.types.expressions.OperatorConventions

import java.util.*

import org.jetbrains.kotlin.cfg.ControlFlowBuilder.PredefinedOperation.*
import org.jetbrains.kotlin.diagnostics.Errors.*
import org.jetbrains.kotlin.lexer.KtTokens.*

class ControlFlowProcessor(private val trace: BindingTrace) {

    private val builder: ControlFlowBuilder = ControlFlowInstructionsGenerator()

    fun generatePseudocode(subroutine: KtElement): Pseudocode {
        val pseudocode = generate(subroutine)
        (pseudocode as PseudocodeImpl).postProcess()
        return pseudocode
    }

    private fun generate(subroutine: KtElement): Pseudocode {
        builder.enterSubroutine(subroutine)
        val cfpVisitor = CFPVisitor(builder)
        if (subroutine is KtDeclarationWithBody && subroutine !is KtSecondaryConstructor) {
            val valueParameters = subroutine.valueParameters
            for (valueParameter in valueParameters) {
                cfpVisitor.generateInstructions(valueParameter)
            }
            val bodyExpression = subroutine.bodyExpression
            if (bodyExpression != null) {
                cfpVisitor.generateInstructions(bodyExpression)
                if (!subroutine.hasBlockBody()) {
                    generateImplicitReturnValue(bodyExpression, subroutine)
                }
            }
        }
        else {
            cfpVisitor.generateInstructions(subroutine)
        }
        return builder.exitSubroutine(subroutine)
    }

    private fun generateImplicitReturnValue(bodyExpression: KtExpression, subroutine: KtElement) {
        val subroutineDescriptor = trace.get(BindingContext.DECLARATION_TO_DESCRIPTOR, subroutine) as CallableDescriptor? ?: return

        val returnType = subroutineDescriptor.returnType
        if (returnType != null && KotlinBuiltIns.isUnit(returnType) && subroutineDescriptor is AnonymousFunctionDescriptor) return

        val returnValue = builder.getBoundValue(bodyExpression) ?: return

        builder.returnValue(bodyExpression, returnValue, subroutine)
    }

    private fun processLocalDeclaration(subroutine: KtDeclaration) {
        val afterDeclaration = builder.createUnboundLabel("after local declaration")

        builder.nondeterministicJump(afterDeclaration, subroutine, null)
        generate(subroutine)
        builder.bindLabel(afterDeclaration)
    }

    private inner class CFPVisitor(private val builder: ControlFlowBuilder) : KtVisitorVoid() {

        private val conditionVisitor = object : KtVisitorVoid() {

            private fun getSubjectExpression(condition: KtWhenCondition): KtExpression? {
                return condition.getStrictParentOfType<KtWhenExpression>()?.subjectExpression
            }

            override fun visitWhenConditionInRange(condition: KtWhenConditionInRange) {
                if (!generateCall(condition.operationReference)) {
                    val rangeExpression = condition.rangeExpression
                    generateInstructions(rangeExpression)
                    createNonSyntheticValue(condition, MagicKind.UNRESOLVED_CALL, rangeExpression)
                }
            }

            override fun visitWhenConditionIsPattern(condition: KtWhenConditionIsPattern) {
                mark(condition)
                createNonSyntheticValue(condition, MagicKind.IS, getSubjectExpression(condition))
            }

            override fun visitWhenConditionWithExpression(condition: KtWhenConditionWithExpression) {
                mark(condition)

                val expression = condition.expression
                generateInstructions(expression)

                val subjectExpression = getSubjectExpression(condition)
                if (subjectExpression != null) {
                    // todo: this can be replaced by equals() invocation (when corresponding resolved call is recorded)
                    createNonSyntheticValue(condition, MagicKind.EQUALS_IN_WHEN_CONDITION, subjectExpression, expression)
                }
                else {
                    copyValue(expression, condition)
                }
            }

            override fun visitKtElement(element: KtElement) {
                throw UnsupportedOperationException("[ControlFlowProcessor] " + element.toString())
            }
        }

        private fun mark(element: KtElement) {
            builder.mark(element)
        }

        fun generateInstructions(element: KtElement?) {
            if (element == null) return
            element.accept(this)
            checkNothingType(element)
        }

        private fun checkNothingType(element: KtElement) {
            if (element !is KtExpression) return

            val expression = KtPsiUtil.deparenthesize(element) ?: return

            if (expression is KtStatementExpression || expression is KtTryExpression
                || expression is KtIfExpression || expression is KtWhenExpression) {
                return
            }

            val type = trace.bindingContext.getType(expression)
            if (type != null && KotlinBuiltIns.isNothing(type)) {
                builder.jumpToError(expression)
            }
        }

        private fun createSyntheticValue(instructionElement: KtElement, kind: MagicKind, vararg from: KtElement): PseudoValue {
            return builder.magic(instructionElement, null, elementsToValues(from.asList()), kind).outputValue
        }

        private fun createNonSyntheticValue(to: KtElement, from: List<KtElement?>, kind: MagicKind): PseudoValue {
            return builder.magic(to, to, elementsToValues(from), kind).outputValue
        }

        private fun createNonSyntheticValue(to: KtElement, kind: MagicKind, vararg from: KtElement?): PseudoValue {
            return createNonSyntheticValue(to, from.asList(), kind)
        }

        private fun mergeValues(from: List<KtExpression>, to: KtExpression) {
            builder.merge(to, elementsToValues(from))
        }

        private fun copyValue(from: KtElement?, to: KtElement) {
            getBoundOrUnreachableValue(from)?.let { builder.bindValue(it, to) }
        }

        private fun getBoundOrUnreachableValue(element: KtElement?): PseudoValue? {
            if (element == null) return null

            val value = builder.getBoundValue(element)
            return if (value != null || element is KtDeclaration) value else builder.newValue(element)
        }

        private fun elementsToValues(from: List<KtElement?>): List<PseudoValue> {
            return from.map { element -> getBoundOrUnreachableValue(element) }.filterNotNull()
        }

        private fun generateInitializer(declaration: KtDeclaration, initValue: PseudoValue) {
            builder.write(declaration, declaration, initValue, getDeclarationAccessTarget(declaration), emptyMap())
        }

        private fun getResolvedCallAccessTarget(element: KtElement?): AccessTarget {
            return element.getResolvedCall(trace.bindingContext)?.let { AccessTarget.Call(it) }
                   ?: AccessTarget.BlackBox
        }

        private fun getDeclarationAccessTarget(element: KtElement): AccessTarget {
            val descriptor = trace.get(BindingContext.DECLARATION_TO_DESCRIPTOR, element)
            return if (descriptor is VariableDescriptor)
                AccessTarget.Declaration(descriptor)
            else
                AccessTarget.BlackBox
        }

        override fun visitParenthesizedExpression(expression: KtParenthesizedExpression) {
            mark(expression)
            val innerExpression = expression.expression
            if (innerExpression != null) {
                generateInstructions(innerExpression)
                copyValue(innerExpression, expression)
            }
        }

        override fun visitAnnotatedExpression(expression: KtAnnotatedExpression) {
            val baseExpression = expression.baseExpression
            if (baseExpression != null) {
                generateInstructions(baseExpression)
                copyValue(baseExpression, expression)
            }
        }

        override fun visitThisExpression(expression: KtThisExpression) {
            val resolvedCall = expression.getResolvedCall(trace.bindingContext)
            if (resolvedCall == null) {
                createNonSyntheticValue(expression, MagicKind.UNRESOLVED_CALL)
                return
            }

            val resultingDescriptor = resolvedCall.resultingDescriptor
            if (resultingDescriptor is ReceiverParameterDescriptor) {
                builder.readVariable(expression, resolvedCall, getReceiverValues(resolvedCall))
            }

            copyValue(expression, expression.instanceReference)
        }

        override fun visitConstantExpression(expression: KtConstantExpression) {
            val constant = ConstantExpressionEvaluator.getConstant(expression, trace.bindingContext)
            builder.loadConstant(expression, constant)
        }

        override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
            val resolvedCall = expression.getResolvedCall(trace.bindingContext)
            if (resolvedCall is VariableAsFunctionResolvedCall) {
                generateCall(resolvedCall.variableCall)
            }
            else if (!generateCall(expression) && expression.parent !is KtCallExpression) {
                createNonSyntheticValue(expression, MagicKind.UNRESOLVED_CALL, generateAndGetReceiverIfAny(expression))
            }
        }

        override fun visitLabeledExpression(expression: KtLabeledExpression) {
            mark(expression)
            val baseExpression = expression.baseExpression
            if (baseExpression != null) {
                generateInstructions(baseExpression)
                copyValue(baseExpression, expression)
            }
        }

        override fun visitBinaryExpression(expression: KtBinaryExpression) {
            val operationReference = expression.operationReference
            val operationType = operationReference.getReferencedNameElementType()

            val left = expression.left
            val right = expression.right
            if (operationType === ANDAND || operationType === OROR) {
                generateBooleanOperation(expression)
            }
            else if (operationType === EQ) {
                visitAssignment(left, getDeferredValue(right), expression)
            }
            else if (OperatorConventions.ASSIGNMENT_OPERATIONS.containsKey(operationType)) {
                val resolvedCall = expression.getResolvedCall(trace.bindingContext)
                if (resolvedCall != null) {
                    val rhsValue = generateCall(resolvedCall).outputValue
                    val assignMethodName = OperatorConventions.getNameForOperationSymbol(expression.operationToken as KtToken)
                    if (resolvedCall.resultingDescriptor.name != assignMethodName) {
                        /* At this point assignment of the form a += b actually means a = a + b
                         * So we first generate call of "+" operation and then use its output pseudo-value
                         * as a right-hand side when generating assignment call
                         */
                        visitAssignment(left, getValueAsFunction(rhsValue), expression)
                    }
                }
                else {
                    generateBothArgumentsAndMark(expression)
                }
            }
            else if (operationType === ELVIS) {
                generateInstructions(left)
                mark(expression)
                val afterElvis = builder.createUnboundLabel("after elvis operator")
                builder.jumpOnTrue(afterElvis, expression, builder.getBoundValue(left))
                generateInstructions(right)
                builder.bindLabel(afterElvis)
                mergeValues(listOf(left, right).filterNotNull(), expression)
            }
            else {
                if (!generateCall(expression)) {
                    generateBothArgumentsAndMark(expression)
                }
            }
        }

        private fun generateBooleanOperation(expression: KtBinaryExpression) {
            val operationType = expression.operationReference.getReferencedNameElementType()
            val left = expression.left
            val right = expression.right

            val resultLabel = builder.createUnboundLabel("result of boolean operation")
            generateInstructions(left)
            if (operationType === ANDAND) {
                builder.jumpOnFalse(resultLabel, expression, builder.getBoundValue(left))
            }
            else {
                builder.jumpOnTrue(resultLabel, expression, builder.getBoundValue(left))
            }
            generateInstructions(right)
            builder.bindLabel(resultLabel)
            val operation = if (operationType === ANDAND) AND else OR
            builder.predefinedOperation(expression, operation, elementsToValues(listOf(left, right).filterNotNull()))
        }

        private fun getValueAsFunction(value: PseudoValue?) = { value }

        private fun getDeferredValue(expression: KtExpression?) = {
            generateInstructions(expression)
            getBoundOrUnreachableValue(expression)
        }

        private fun generateBothArgumentsAndMark(expression: KtBinaryExpression) {
            val left = KtPsiUtil.deparenthesize(expression.left)
            if (left != null) {
                generateInstructions(left)
            }
            val right = expression.right
            if (right != null) {
                generateInstructions(right)
            }
            mark(expression)
            createNonSyntheticValue(expression, MagicKind.UNRESOLVED_CALL, left, right)
        }

        private fun visitAssignment(
                lhs: KtExpression?,
                rhsDeferredValue: () -> PseudoValue?,
                parentExpression: KtExpression
        ) {
            val left = KtPsiUtil.deparenthesize(lhs)
            if (left == null) {
                val arguments = rhsDeferredValue()?.let { listOf(it) } ?: emptyList()
                builder.magic(parentExpression, parentExpression, arguments, MagicKind.UNSUPPORTED_ELEMENT)
                return
            }

            if (left is KtArrayAccessExpression) {
                generateArrayAssignment(left, rhsDeferredValue, parentExpression)
                return
            }

            var receiverValues: Map<PseudoValue, ReceiverValue> = SmartFMap.emptyMap<PseudoValue, ReceiverValue>()
            var accessTarget: AccessTarget = AccessTarget.BlackBox
            if (left is KtSimpleNameExpression || left is KtQualifiedExpression) {
                accessTarget = getResolvedCallAccessTarget(left.getQualifiedElementSelector())
                if (accessTarget is AccessTarget.Call) {
                    receiverValues = getReceiverValues(accessTarget.resolvedCall)
                }
            }
            else if (left is KtProperty) {
                accessTarget = getDeclarationAccessTarget(left)
            }

            if (accessTarget === AccessTarget.BlackBox && left !is KtProperty) {
                generateInstructions(left)
                createSyntheticValue(left, MagicKind.VALUE_CONSUMER, left)
            }

            val rightValue = rhsDeferredValue.invoke()
            val rValue = rightValue ?: createSyntheticValue(parentExpression, MagicKind.UNRECOGNIZED_WRITE_RHS)
            builder.write(parentExpression, left, rValue, accessTarget, receiverValues)
        }

        private fun generateArrayAssignment(
                lhs: KtArrayAccessExpression,
                rhsDeferredValue: () -> PseudoValue?,
                parentExpression: KtExpression
        ) {
            val setResolvedCall = trace.get(BindingContext.INDEXED_LVALUE_SET, lhs)

            if (setResolvedCall == null) {
                generateArrayAccess(lhs, null)

                val arguments = listOf(getBoundOrUnreachableValue(lhs), rhsDeferredValue.invoke()).filterNotNull()
                builder.magic(parentExpression, parentExpression, arguments, MagicKind.UNRESOLVED_CALL)

                return
            }

            // In case of simple ('=') array assignment mark instruction is not generated yet, so we put it before generating "set" call
            if ((parentExpression as KtOperationExpression).operationReference.getReferencedNameElementType() === EQ) {
                mark(lhs)
            }

            generateInstructions(lhs.arrayExpression)

            val receiverValues = getReceiverValues(setResolvedCall)
            val argumentValues = getArraySetterArguments(rhsDeferredValue, setResolvedCall)

            builder.call(parentExpression, setResolvedCall, receiverValues, argumentValues)
        }

        /* We assume that assignment right-hand side corresponds to the last argument of the call
        *  So receiver instructions/pseudo-values are generated for all arguments except the last one which is replaced
        *  by pre-generated pseudo-value
        *  For example, assignment a[1, 2] += 3 means a.set(1, 2, a.get(1) + 3), so in order to generate "set" call
        *  we first generate instructions for 1 and 2 whereas 3 is replaced by pseudo-value corresponding to "a.get(1) + 3"
        */
        private fun getArraySetterArguments(
                rhsDeferredValue: () -> PseudoValue?,
                setResolvedCall: ResolvedCall<FunctionDescriptor>
        ): SmartFMap<PseudoValue, ValueParameterDescriptor> {
            val valueArguments = setResolvedCall.resultingDescriptor.valueParameters.flatMapTo(
                    ArrayList<ValueArgument>()
            ) { descriptor -> setResolvedCall.valueArguments[descriptor]?.arguments ?: emptyList() }

            val rhsArgument = valueArguments.lastOrNull()
            var argumentValues = SmartFMap.emptyMap<PseudoValue, ValueParameterDescriptor>()
            for (valueArgument in valueArguments) {
                val argumentMapping = setResolvedCall.getArgumentMapping(valueArgument)
                if (argumentMapping.isError() || argumentMapping !is ArgumentMatch) continue

                val parameterDescriptor = argumentMapping.valueParameter
                if (valueArgument !== rhsArgument) {
                    argumentValues = generateValueArgument(valueArgument, parameterDescriptor, argumentValues)
                }
                else {
                    val rhsValue = rhsDeferredValue.invoke()
                    if (rhsValue != null) {
                        argumentValues = argumentValues.plus(rhsValue, parameterDescriptor)
                    }
                }
            }
            return argumentValues
        }

        private fun generateArrayAccess(arrayAccessExpression: KtArrayAccessExpression, resolvedCall: ResolvedCall<*>?) {
            if (builder.getBoundValue(arrayAccessExpression) != null) return
            mark(arrayAccessExpression)
            if (!checkAndGenerateCall(resolvedCall)) {
                generateArrayAccessWithoutCall(arrayAccessExpression)
            }
        }

        private fun generateArrayAccessWithoutCall(arrayAccessExpression: KtArrayAccessExpression) {
            createNonSyntheticValue(arrayAccessExpression, generateArrayAccessArguments(arrayAccessExpression), MagicKind.UNRESOLVED_CALL)
        }

        private fun generateArrayAccessArguments(arrayAccessExpression: KtArrayAccessExpression): List<KtExpression> {
            val inputExpressions = ArrayList<KtExpression>()

            val arrayExpression = arrayAccessExpression.arrayExpression
            if (arrayExpression != null) {
                inputExpressions.add(arrayExpression)
            }
            generateInstructions(arrayExpression)

            for (index in arrayAccessExpression.indexExpressions) {
                generateInstructions(index)
                inputExpressions.add(index)
            }

            return inputExpressions
        }

        override fun visitUnaryExpression(expression: KtUnaryExpression) {
            val operationSign = expression.operationReference
            val operationType = operationSign.getReferencedNameElementType()
            val baseExpression = expression.baseExpression ?: return
            if (KtTokens.EXCLEXCL === operationType) {
                generateInstructions(baseExpression)
                builder.predefinedOperation(expression, NOT_NULL_ASSERTION, elementsToValues(listOf(baseExpression)))
                return
            }

            val incrementOrDecrement = isIncrementOrDecrement(operationType)
            val resolvedCall = expression.getResolvedCall(trace.bindingContext)

            val rhsValue: PseudoValue?
            if (resolvedCall != null) {
                rhsValue = generateCall(resolvedCall).outputValue
            }
            else {
                generateInstructions(baseExpression)
                rhsValue = createNonSyntheticValue(expression, MagicKind.UNRESOLVED_CALL, baseExpression)
            }

            if (incrementOrDecrement) {
                visitAssignment(baseExpression, getValueAsFunction(rhsValue), expression)
                if (expression is KtPostfixExpression) {
                    copyValue(baseExpression, expression)
                }
            }
        }

        private fun isIncrementOrDecrement(operationType: IElementType): Boolean {
            return operationType === KtTokens.PLUSPLUS || operationType === KtTokens.MINUSMINUS
        }

        override fun visitIfExpression(expression: KtIfExpression) {
            mark(expression)
            val branches = ArrayList<KtExpression>(2)
            val condition = expression.condition
            generateInstructions(condition)
            val elseLabel = builder.createUnboundLabel("else branch")
            builder.jumpOnFalse(elseLabel, expression, builder.getBoundValue(condition))
            val thenBranch = expression.then
            if (thenBranch != null) {
                branches.add(thenBranch)
                generateInstructions(thenBranch)
            }
            else {
                builder.loadUnit(expression)
            }
            val resultLabel = builder.createUnboundLabel("'if' expression result")
            builder.jump(resultLabel, expression)
            builder.bindLabel(elseLabel)
            val elseBranch = expression.`else`
            if (elseBranch != null) {
                branches.add(elseBranch)
                generateInstructions(elseBranch)
            }
            else {
                builder.loadUnit(expression)
            }
            builder.bindLabel(resultLabel)
            mergeValues(branches, expression)
        }

        private inner class FinallyBlockGenerator(private val finallyBlock: KtFinallySection?) {
            private var startFinally: Label? = null
            private var finishFinally: Label? = null

            fun generate() {
                val finalExpression = finallyBlock?.finalExpression ?: return
                startFinally?.let {
                    assert(finishFinally != null) { "startFinally label is set to $startFinally but finishFinally label is not set" }
                    builder.repeatPseudocode(it, finishFinally!!)
                    return
                }
                builder.createUnboundLabel("start finally").let {
                    startFinally = it
                    builder.bindLabel(it)
                }
                generateInstructions(finalExpression)
                builder.createUnboundLabel("finish finally").let {
                    finishFinally = it
                    builder.bindLabel(it)
                }
            }
        }

        override fun visitTryExpression(expression: KtTryExpression) {
            mark(expression)

            val finallyBlock = expression.finallyBlock
            val finallyBlockGenerator = FinallyBlockGenerator(finallyBlock)
            val hasFinally = finallyBlock != null
            if (hasFinally) {
                builder.enterTryFinally(object : GenerationTrigger {
                    private var working = false

                    override fun generate() {
                        // This checks are needed for the case of having e.g. return inside finally: 'try {return} finally{return}'
                        if (working) return
                        working = true
                        finallyBlockGenerator.generate()
                        working = false
                    }
                })
            }

            val onExceptionToFinallyBlock = generateTryAndCatches(expression)

            if (hasFinally) {
                assert(onExceptionToFinallyBlock != null) { "No finally label generated: " + expression.text }

                builder.exitTryFinally()

                val skipFinallyToErrorBlock = builder.createUnboundLabel("skipFinallyToErrorBlock")
                builder.jump(skipFinallyToErrorBlock, expression)
                builder.bindLabel(onExceptionToFinallyBlock!!)
                finallyBlockGenerator.generate()
                builder.jumpToError(expression)
                builder.bindLabel(skipFinallyToErrorBlock)

                finallyBlockGenerator.generate()
            }

            val branches = ArrayList<KtExpression>()
            branches.add(expression.tryBlock)
            for (catchClause in expression.catchClauses) {
                catchClause.catchBody?.let { branches.add(it) }
            }
            mergeValues(branches, expression)
        }

        // Returns label for 'finally' block
        private fun generateTryAndCatches(expression: KtTryExpression): Label? {
            val catchClauses = expression.catchClauses
            val hasCatches = !catchClauses.isEmpty()

            var onException: Label? = null
            if (hasCatches) {
                onException = builder.createUnboundLabel("onException")
                builder.nondeterministicJump(onException, expression, null)
            }

            var onExceptionToFinallyBlock: Label? = null
            if (expression.finallyBlock != null) {
                onExceptionToFinallyBlock = builder.createUnboundLabel("onExceptionToFinallyBlock")
                builder.nondeterministicJump(onExceptionToFinallyBlock, expression, null)
            }

            val tryBlock = expression.tryBlock
            generateInstructions(tryBlock)

            if (hasCatches && onException != null) {
                val afterCatches = builder.createUnboundLabel("afterCatches")
                builder.jump(afterCatches, expression)

                builder.bindLabel(onException)
                val catchLabels = Lists.newLinkedList<Label>()
                val catchClausesSize = catchClauses.size
                for (i in 0..catchClausesSize - 1 - 1) {
                    catchLabels.add(builder.createUnboundLabel("catch " + i))
                }
                if (!catchLabels.isEmpty()) {
                    builder.nondeterministicJump(catchLabels, expression)
                }
                var isFirst = true
                for (catchClause in catchClauses) {
                    builder.enterLexicalScope(catchClause)
                    if (!isFirst) {
                        builder.bindLabel(catchLabels.remove())
                    }
                    else {
                        isFirst = false
                    }
                    val catchParameter = catchClause.catchParameter
                    if (catchParameter != null) {
                        builder.declareParameter(catchParameter)
                        generateInitializer(catchParameter, createSyntheticValue(catchParameter, MagicKind.FAKE_INITIALIZER))
                    }
                    generateInstructions(catchClause.catchBody)
                    builder.jump(afterCatches, expression)
                    builder.exitLexicalScope(catchClause)
                }

                builder.bindLabel(afterCatches)
            }

            return onExceptionToFinallyBlock
        }

        override fun visitWhileExpression(expression: KtWhileExpression) {
            val loopInfo = builder.enterLoop(expression)

            builder.bindLabel(loopInfo.conditionEntryPoint)
            val condition = expression.condition
            generateInstructions(condition)
            mark(expression)
            if (!CompileTimeConstantUtils.canBeReducedToBooleanConstant(condition, trace.bindingContext, true)) {
                builder.jumpOnFalse(loopInfo.exitPoint, expression, builder.getBoundValue(condition))
            }
            else {
                assert(condition != null) { "Invalid while condition: " + expression.text }
                createSyntheticValue(condition!!, MagicKind.VALUE_CONSUMER, condition)
            }

            builder.enterLoopBody(expression)
            generateInstructions(expression.body)
            builder.jump(loopInfo.entryPoint, expression)
            builder.exitLoopBody(expression)
            builder.bindLabel(loopInfo.exitPoint)
            builder.loadUnit(expression)
        }

        override fun visitDoWhileExpression(expression: KtDoWhileExpression) {
            builder.enterLexicalScope(expression)
            mark(expression)
            val loopInfo = builder.enterLoop(expression)

            builder.enterLoopBody(expression)
            generateInstructions(expression.body)
            builder.exitLoopBody(expression)
            builder.bindLabel(loopInfo.conditionEntryPoint)
            generateInstructions(expression.condition)
            builder.jumpOnTrue(loopInfo.entryPoint, expression, builder.getBoundValue(expression.condition))
            builder.bindLabel(loopInfo.exitPoint)
            builder.loadUnit(expression)
            builder.exitLexicalScope(expression)
        }

        override fun visitForExpression(expression: KtForExpression) {
            builder.enterLexicalScope(expression)

            generateInstructions(expression.loopRange)
            declareLoopParameter(expression)

            // TODO : primitive cases
            val loopInfo = builder.enterLoop(expression)

            builder.bindLabel(loopInfo.conditionEntryPoint)
            builder.nondeterministicJump(loopInfo.exitPoint, expression, null)


            writeLoopParameterAssignment(expression)

            mark(expression)
            builder.enterLoopBody(expression)
            generateInstructions(expression.body)
            builder.jump(loopInfo.entryPoint, expression)

            builder.exitLoopBody(expression)
            builder.bindLabel(loopInfo.exitPoint)
            builder.loadUnit(expression)
            builder.exitLexicalScope(expression)
        }

        private fun declareLoopParameter(expression: KtForExpression) {
            val loopParameter = expression.loopParameter
            val multiDeclaration = expression.destructuringParameter
            if (loopParameter != null) {
                builder.declareParameter(loopParameter)
            }
            else if (multiDeclaration != null) {
                visitDestructuringDeclaration(multiDeclaration, false)
            }
        }

        private fun writeLoopParameterAssignment(expression: KtForExpression) {
            val loopParameter = expression.loopParameter
            val multiDeclaration = expression.destructuringParameter
            val loopRange = expression.loopRange

            val value = builder.magic(
                    loopRange ?: expression,
                    null,
                    ContainerUtil.createMaybeSingletonList(builder.getBoundValue(loopRange)),
                    MagicKind.LOOP_RANGE_ITERATION
            ).outputValue

            if (loopParameter != null) {
                generateInitializer(loopParameter, value)
            }
            else if (multiDeclaration != null) {
                for (entry in multiDeclaration.entries) {
                    generateInitializer(entry, value)
                }
            }
        }

        override fun visitBreakExpression(expression: KtBreakExpression) {
            val loop = getCorrespondingLoop(expression)
            if (loop != null) {
                checkJumpDoesNotCrossFunctionBoundary(expression, loop)
                builder.getExitPoint(loop)?.let { builder.jump(it, expression) }
            }
        }

        override fun visitContinueExpression(expression: KtContinueExpression) {
            val loop = getCorrespondingLoop(expression)
            if (loop != null) {
                checkJumpDoesNotCrossFunctionBoundary(expression, loop)
                builder.jump(builder.getConditionEntryPoint(loop), expression)
            }
        }

        private fun getCorrespondingLoop(expression: KtExpressionWithLabel): KtElement? {
            val labelName = expression.getLabelName()
            val loop: KtLoopExpression?
            if (labelName != null) {
                val targetLabel = expression.getTargetLabel()!!
                val labeledElement = trace.get(BindingContext.LABEL_TARGET, targetLabel)
                if (labeledElement is KtLoopExpression) {
                    loop = labeledElement
                }
                else {
                    trace.report(NOT_A_LOOP_LABEL.on(expression, targetLabel.text))
                    loop = null
                }
            }
            else {
                loop = builder.currentLoop
                if (loop == null) {
                    trace.report(BREAK_OR_CONTINUE_OUTSIDE_A_LOOP.on(expression))
                }
                else {
                    val whenExpression = PsiTreeUtil.getParentOfType(expression, KtWhenExpression::class.java, true,
                                                                     KtLoopExpression::class.java)
                    if (whenExpression != null) {
                        trace.report(BREAK_OR_CONTINUE_IN_WHEN.on(expression))
                    }
                }
            }
            loop?.body?.let {
                if (!it.textRange.contains(expression.textRange)) {
                    trace.report(BREAK_OR_CONTINUE_OUTSIDE_A_LOOP.on(expression))
                    return null
                }
            }
            return loop
        }

        private fun checkJumpDoesNotCrossFunctionBoundary(jumpExpression: KtExpressionWithLabel, jumpTarget: KtElement) {
            val bindingContext = trace.bindingContext

            val labelExprEnclosingFunc = BindingContextUtils.getEnclosingFunctionDescriptor(bindingContext, jumpExpression)
            val labelTargetEnclosingFunc = BindingContextUtils.getEnclosingFunctionDescriptor(bindingContext, jumpTarget)
            if (labelExprEnclosingFunc !== labelTargetEnclosingFunc) {
                trace.report(BREAK_OR_CONTINUE_JUMPS_ACROSS_FUNCTION_BOUNDARY.on(jumpExpression))
            }
        }

        override fun visitReturnExpression(expression: KtReturnExpression) {
            val returnedExpression = expression.returnedExpression
            if (returnedExpression != null) {
                generateInstructions(returnedExpression)
            }
            val labelElement = expression.getTargetLabel()
            val subroutine: KtElement?
            val labelName = expression.getLabelName()
            if (labelElement != null && labelName != null) {
                val labeledElement = trace.get(BindingContext.LABEL_TARGET, labelElement)
                if (labeledElement != null) {
                    assert(labeledElement is KtElement)
                    subroutine = labeledElement as KtElement?
                }
                else {
                    subroutine = null
                }
            }
            else {
                subroutine = builder.returnSubroutine
                // TODO : a context check
            }

            if (subroutine is KtFunction || subroutine is KtPropertyAccessor) {
                val returnValue = if (returnedExpression != null) builder.getBoundValue(returnedExpression) else null
                if (returnValue == null) {
                    builder.returnNoValue(expression, subroutine)
                }
                else {
                    builder.returnValue(expression, returnValue, subroutine)
                }
            }
            else {
                createNonSyntheticValue(expression, MagicKind.UNSUPPORTED_ELEMENT, returnedExpression)
            }
        }

        override fun visitParameter(parameter: KtParameter) {
            builder.declareParameter(parameter)
            val defaultValue = parameter.defaultValue
            if (defaultValue != null) {
                val skipDefaultValue = builder.createUnboundLabel("after default value for parameter " + parameter.name!!)
                builder.nondeterministicJump(skipDefaultValue, defaultValue, null)
                generateInstructions(defaultValue)
                builder.bindLabel(skipDefaultValue)
            }
            generateInitializer(parameter, computePseudoValueForParameter(parameter))
        }

        private fun computePseudoValueForParameter(parameter: KtParameter): PseudoValue {
            val syntheticValue = createSyntheticValue(parameter, MagicKind.FAKE_INITIALIZER)
            val defaultValue = builder.getBoundValue(parameter.defaultValue) ?: return syntheticValue
            return builder.merge(parameter, Lists.newArrayList(defaultValue, syntheticValue)).outputValue
        }

        override fun visitBlockExpression(expression: KtBlockExpression) {
            val declareLexicalScope = !isBlockInDoWhile(expression)
            if (declareLexicalScope) {
                builder.enterLexicalScope(expression)
            }
            mark(expression)
            val statements = expression.statements
            for (statement in statements) {
                generateInstructions(statement)
            }
            if (statements.isEmpty()) {
                builder.loadUnit(expression)
            }
            else {
                copyValue(statements.lastOrNull(), expression)
            }
            if (declareLexicalScope) {
                builder.exitLexicalScope(expression)
            }
        }

        private fun isBlockInDoWhile(expression: KtBlockExpression): Boolean {
            val parent = expression.parent ?: return false
            return parent.parent is KtDoWhileExpression
        }

        private fun visitFunction(function: KtFunction) {
            processLocalDeclaration(function)
            val isAnonymousFunction = function is KtFunctionLiteral || function.name == null
            if (isAnonymousFunction || function.isLocal && function.parent !is KtBlockExpression) {
                builder.createLambda(function)
            }
        }

        override fun visitNamedFunction(function: KtNamedFunction) {
            visitFunction(function)
        }

        override fun visitLambdaExpression(lambdaExpression: KtLambdaExpression) {
            mark(lambdaExpression)
            val functionLiteral = lambdaExpression.functionLiteral
            visitFunction(functionLiteral)
            copyValue(functionLiteral, lambdaExpression)
        }

        override fun visitQualifiedExpression(expression: KtQualifiedExpression) {
            mark(expression)
            val selectorExpression = expression.selectorExpression
            val receiverExpression = expression.receiverExpression

            // todo: replace with selectorExpresion != null after parser is fixed
            if (selectorExpression is KtCallExpression || selectorExpression is KtSimpleNameExpression) {
                generateInstructions(selectorExpression)
                copyValue(selectorExpression, expression)
            }
            else {
                generateInstructions(receiverExpression)
                createNonSyntheticValue(expression, MagicKind.UNSUPPORTED_ELEMENT, receiverExpression)
            }
        }

        override fun visitCallExpression(expression: KtCallExpression) {
            if (!generateCall(expression)) {
                val inputExpressions = ArrayList<KtExpression>()
                for (argument in expression.valueArguments) {
                    val argumentExpression = argument.getArgumentExpression()
                    if (argumentExpression != null) {
                        generateInstructions(argumentExpression)
                        inputExpressions.add(argumentExpression)
                    }
                }
                val calleeExpression = expression.calleeExpression
                generateInstructions(calleeExpression)
                if (calleeExpression != null) {
                    inputExpressions.add(calleeExpression)
                    generateAndGetReceiverIfAny(expression)?.let { inputExpressions.add(it) }
                }

                mark(expression)
                createNonSyntheticValue(expression, inputExpressions, MagicKind.UNRESOLVED_CALL)
            }
        }

        private fun generateAndGetReceiverIfAny(expression: KtExpression): KtExpression? {
            val parent = expression.parent
            if (parent !is KtQualifiedExpression) return null

            if (parent.selectorExpression !== expression) return null

            val receiverExpression = parent.receiverExpression
            generateInstructions(receiverExpression)

            return receiverExpression
        }

        override fun visitProperty(property: KtProperty) {
            builder.declareVariable(property)
            val initializer = property.initializer
            if (initializer != null) {
                visitAssignment(property, getDeferredValue(initializer), property)
            }
            val delegate = property.delegateExpression
            if (delegate != null) {
                // We do not want to have getDeferredValue(delegate) here, because delegate value will be read anyway later
                visitAssignment(property, getDeferredValue(null), property)
                generateInstructions(delegate)
                if (builder.getBoundValue(delegate) != null) {
                    createSyntheticValue(property, MagicKind.VALUE_CONSUMER, delegate)
                }
            }

            if (KtPsiUtil.isLocal(property)) {
                for (accessor in property.accessors) {
                    generateInstructions(accessor)
                }
            }
        }

        override fun visitDestructuringDeclaration(declaration: KtDestructuringDeclaration) {
            visitDestructuringDeclaration(declaration, true)
        }

        private fun visitDestructuringDeclaration(declaration: KtDestructuringDeclaration, generateWriteForEntries: Boolean) {
            val initializer = declaration.initializer
            generateInstructions(initializer)
            for (entry in declaration.entries) {
                builder.declareVariable(entry)

                val resolvedCall = trace.get(BindingContext.COMPONENT_RESOLVED_CALL, entry)

                val writtenValue: PseudoValue?
                if (resolvedCall != null) {
                    writtenValue = builder.call(
                            entry,
                            resolvedCall,
                            getReceiverValues(resolvedCall),
                            emptyMap<PseudoValue, ValueParameterDescriptor>()).outputValue
                }
                else {
                    writtenValue = initializer?.let { createSyntheticValue(entry, MagicKind.UNRESOLVED_CALL, it) }
                }

                if (generateWriteForEntries) {
                    generateInitializer(entry, writtenValue ?: createSyntheticValue(entry, MagicKind.FAKE_INITIALIZER))
                }
            }
        }

        override fun visitPropertyAccessor(accessor: KtPropertyAccessor) {
            processLocalDeclaration(accessor)
        }

        override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS) {
            mark(expression)

            val operationType = expression.operationReference.getReferencedNameElementType()
            val left = expression.left
            if (operationType === KtTokens.AS_KEYWORD || operationType === KtTokens.`AS_SAFE`) {
                generateInstructions(left)
                if (getBoundOrUnreachableValue(left) != null) {
                    createNonSyntheticValue(expression, MagicKind.CAST, left)
                }
            }
            else {
                visitKtElement(expression)
                createNonSyntheticValue(expression, MagicKind.UNSUPPORTED_ELEMENT, left)
            }
        }

        override fun visitThrowExpression(expression: KtThrowExpression) {
            mark(expression)

            val thrownExpression = expression.thrownExpression ?: return

            generateInstructions(thrownExpression)

            val thrownValue = builder.getBoundValue(thrownExpression) ?: return

            builder.throwException(expression, thrownValue)
        }

        override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
            generateArrayAccess(expression, trace.get(BindingContext.INDEXED_LVALUE_GET, expression))
        }

        override fun visitIsExpression(expression: KtIsExpression) {
            mark(expression)
            val left = expression.leftHandSide
            generateInstructions(left)
            createNonSyntheticValue(expression, MagicKind.IS, left)
        }

        override fun visitWhenExpression(expression: KtWhenExpression) {
            mark(expression)

            val subjectExpression = expression.subjectExpression
            if (subjectExpression != null) {
                generateInstructions(subjectExpression)
            }

            val branches = ArrayList<KtExpression>()

            val doneLabel = builder.createUnboundLabel("after 'when' expression")

            var nextLabel: Label? = null
            val iterator = expression.entries.iterator()
            while (iterator.hasNext()) {
                val whenEntry = iterator.next()
                mark(whenEntry)

                val isElse = whenEntry.isElse
                if (isElse) {
                    if (iterator.hasNext()) {
                        trace.report(ELSE_MISPLACED_IN_WHEN.on(whenEntry))
                    }
                }
                val bodyLabel = builder.createUnboundLabel("'when' entry body")

                val conditions = whenEntry.conditions
                for (i in conditions.indices) {
                    val condition = conditions[i]
                    condition.accept(conditionVisitor)
                    if (i + 1 < conditions.size) {
                        builder.nondeterministicJump(bodyLabel, expression, builder.getBoundValue(condition))
                    }
                }

                if (!isElse) {
                    nextLabel = builder.createUnboundLabel("next 'when' entry")
                    val lastCondition = conditions.lastOrNull()
                    builder.nondeterministicJump(nextLabel, expression, builder.getBoundValue(lastCondition))
                }

                builder.bindLabel(bodyLabel)
                val whenEntryExpression = whenEntry.expression
                if (whenEntryExpression != null) {
                    generateInstructions(whenEntryExpression)
                    branches.add(whenEntryExpression)
                }
                builder.jump(doneLabel, expression)

                if (!isElse && nextLabel != null) {
                    builder.bindLabel(nextLabel)
                    // For the last entry of exhaustive when,
                    // attempt to jump further should lead to error, not to "done"
                    if (!iterator.hasNext() && WhenChecker.isWhenExhaustive(expression, trace)) {
                        builder.magic(expression, null, emptyList<PseudoValue>(), MagicKind.EXHAUSTIVE_WHEN_ELSE)
                    }
                }
            }
            builder.bindLabel(doneLabel)

            mergeValues(branches, expression)
        }

        override fun visitObjectLiteralExpression(expression: KtObjectLiteralExpression) {
            mark(expression)
            val declaration = expression.objectDeclaration
            generateInstructions(declaration)

            builder.createAnonymousObject(expression)
        }

        override fun visitObjectDeclaration(objectDeclaration: KtObjectDeclaration) {
            generateHeaderDelegationSpecifiers(objectDeclaration)
            generateInitializersForScriptClassOrObject(objectDeclaration)
            generateDeclarationForLocalClassOrObjectIfNeeded(objectDeclaration)
        }

        override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
            mark(expression)

            val inputExpressions = ArrayList<KtExpression>()
            for (entry in expression.entries) {
                if (entry is KtStringTemplateEntryWithExpression) {
                    val entryExpression = entry.getExpression()
                    generateInstructions(entryExpression)
                    if (entryExpression != null) {
                        inputExpressions.add(entryExpression)
                    }
                }
            }
            builder.loadStringTemplate(expression, elementsToValues(inputExpressions))
        }

        override fun visitTypeProjection(typeProjection: KtTypeProjection) {
            // TODO : Support Type Arguments. Companion object may be initialized at this point");
        }

        override fun visitAnonymousInitializer(classInitializer: KtAnonymousInitializer) {
            generateInstructions(classInitializer.body)
        }

        private fun generateHeaderDelegationSpecifiers(classOrObject: KtClassOrObject) {
            for (specifier in classOrObject.getSuperTypeListEntries()) {
                generateInstructions(specifier)
            }
        }

        private fun generateInitializersForScriptClassOrObject(classOrObject: KtDeclarationContainer) {
            for (declaration in classOrObject.declarations) {
                if (declaration is KtProperty || declaration is KtAnonymousInitializer) {
                    generateInstructions(declaration)
                }
            }
        }

        override fun visitClass(klass: KtClass) {
            if (klass.hasPrimaryConstructor()) {
                processParameters(klass.getPrimaryConstructorParameters())

                // delegation specifiers of primary constructor, anonymous class and property initializers
                generateHeaderDelegationSpecifiers(klass)
                generateInitializersForScriptClassOrObject(klass)
            }

            generateDeclarationForLocalClassOrObjectIfNeeded(klass)
        }

        override fun visitScript(script: KtScript) {
            generateInitializersForScriptClassOrObject(script)
        }

        private fun generateDeclarationForLocalClassOrObjectIfNeeded(classOrObject: KtClassOrObject) {
            if (classOrObject.isLocal()) {
                for (declaration in classOrObject.declarations) {
                    if (declaration is KtSecondaryConstructor ||
                        declaration is KtProperty ||
                        declaration is KtAnonymousInitializer) {
                        continue
                    }
                    generateInstructions(declaration)
                }
            }
        }

        private fun processParameters(parameters: List<KtParameter>) {
            for (parameter in parameters) {
                generateInstructions(parameter)
            }
        }

        override fun visitSecondaryConstructor(constructor: KtSecondaryConstructor) {
            val classOrObject = PsiTreeUtil.getParentOfType(constructor, KtClassOrObject::class.java) ?: error("Guaranteed by parsing contract")

            processParameters(constructor.valueParameters)
            generateCallOrMarkUnresolved(constructor.getDelegationCall())

            if (!constructor.getDelegationCall().isCallToThis) {
                generateInitializersForScriptClassOrObject(classOrObject)
            }

            generateInstructions(constructor.bodyExpression)
        }

        override fun visitSuperTypeCallEntry(call: KtSuperTypeCallEntry) {
            generateCallOrMarkUnresolved(call)
        }

        private fun generateCallOrMarkUnresolved(call: KtCallElement) {
            if (!generateCall(call)) {
                val arguments = call.valueArguments.map { valueArgument -> valueArgument.getArgumentExpression() }.filterNotNull()

                for (argument in arguments) {
                    generateInstructions(argument)
                }
                createNonSyntheticValue(call, arguments, MagicKind.UNRESOLVED_CALL)
            }
        }

        override fun visitDelegatedSuperTypeEntry(specifier: KtDelegatedSuperTypeEntry) {
            val delegateExpression = specifier.delegateExpression
            generateInstructions(delegateExpression)
            if (delegateExpression != null) {
                createSyntheticValue(specifier, MagicKind.VALUE_CONSUMER, delegateExpression)
            }
        }

        override fun visitSuperTypeEntry(specifier: KtSuperTypeEntry) {
            // Do not generate UNSUPPORTED_ELEMENT here
        }

        override fun visitSuperTypeList(list: KtSuperTypeList) {
            list.acceptChildren(this)
        }

        override fun visitKtFile(file: KtFile) {
            for (declaration in file.declarations) {
                if (declaration is KtProperty) {
                    generateInstructions(declaration)
                }
            }
        }

        override fun visitDoubleColonExpression(expression: KtDoubleColonExpression) {
            mark(expression)
            createNonSyntheticValue(expression, MagicKind.CALLABLE_REFERENCE)
        }

        override fun visitKtElement(element: KtElement) {
            createNonSyntheticValue(element, MagicKind.UNSUPPORTED_ELEMENT)
        }

        private fun generateCall(callElement: KtElement): Boolean {
            return checkAndGenerateCall(callElement.getResolvedCall(trace.bindingContext))
        }

        private fun checkAndGenerateCall(resolvedCall: ResolvedCall<*>?): Boolean {
            if (resolvedCall == null) return false
            generateCall(resolvedCall)
            return true
        }

        private fun generateCall(resolvedCall: ResolvedCall<*>): InstructionWithValue {
            val callElement = resolvedCall.call.callElement

            val receivers = getReceiverValues(resolvedCall)

            var parameterValues = SmartFMap.emptyMap<PseudoValue, ValueParameterDescriptor>()
            for (argument in resolvedCall.call.valueArguments) {
                val argumentMapping = resolvedCall.getArgumentMapping(argument)
                val argumentExpression = argument.getArgumentExpression()
                if (argumentMapping is ArgumentMatch) {
                    parameterValues = generateValueArgument(argument, argumentMapping.valueParameter, parameterValues)
                }
                else if (argumentExpression != null) {
                    generateInstructions(argumentExpression)
                    createSyntheticValue(argumentExpression, MagicKind.VALUE_CONSUMER, argumentExpression)
                }
            }

            if (resolvedCall.resultingDescriptor is VariableDescriptor) {
                // If a callee of the call is just a variable (without 'invoke'), 'read variable' is generated.
                // todo : process arguments for such a case (KT-5387)
                val callExpression = callElement as? KtExpression ?: error("Variable-based call without callee expression: " + callElement.text)
                assert(parameterValues.isEmpty()) { "Variable-based call with non-empty argument list: " + callElement.text }
                return builder.readVariable(callExpression, resolvedCall, receivers)
            }

            mark(resolvedCall.call.callElement)
            return builder.call(callElement, resolvedCall, receivers, parameterValues)
        }

        private fun getReceiverValues(resolvedCall: ResolvedCall<*>): Map<PseudoValue, ReceiverValue> {
            var varCallResult: PseudoValue? = null
            var explicitReceiver: ReceiverValue? = null
            if (resolvedCall is VariableAsFunctionResolvedCall) {
                varCallResult = generateCall(resolvedCall.variableCall).outputValue

                val kind = resolvedCall.explicitReceiverKind
                //noinspection EnumSwitchStatementWhichMissesCases
                when (kind) {
                    ExplicitReceiverKind.DISPATCH_RECEIVER -> explicitReceiver = resolvedCall.dispatchReceiver
                    ExplicitReceiverKind.EXTENSION_RECEIVER, ExplicitReceiverKind.BOTH_RECEIVERS -> explicitReceiver = resolvedCall.extensionReceiver as ReceiverValue?
                    ExplicitReceiverKind.NO_EXPLICIT_RECEIVER -> {}
                }
            }

            var receiverValues = SmartFMap.emptyMap<PseudoValue, ReceiverValue>()
            if (explicitReceiver != null && varCallResult != null) {
                receiverValues = receiverValues.plus(varCallResult, explicitReceiver)
            }
            val callElement = resolvedCall.call.callElement
            receiverValues = getReceiverValues(callElement, resolvedCall.dispatchReceiver, receiverValues)
            receiverValues = getReceiverValues(callElement, resolvedCall.extensionReceiver as ReceiverValue?, receiverValues)
            return receiverValues
        }

        private fun getReceiverValues(
                callElement: KtElement,
                receiver: ReceiverValue?,
                receiverValuesArg: SmartFMap<PseudoValue, ReceiverValue>
        ): SmartFMap<PseudoValue, ReceiverValue> {
            var receiverValues = receiverValuesArg
            if (receiver == null || receiverValues.containsValue(receiver)) return receiverValues

            when (receiver) {
                is ImplicitReceiver -> {
                    receiverValues = receiverValues.plus(createSyntheticValue(callElement, MagicKind.IMPLICIT_RECEIVER), receiver)
                }
                is ExpressionReceiver -> {
                    val expression = receiver.expression
                    if (builder.getBoundValue(expression) == null) {
                        generateInstructions(expression)
                    }

                    val receiverPseudoValue = getBoundOrUnreachableValue(expression)
                    if (receiverPseudoValue != null) {
                        receiverValues = receiverValues.plus(receiverPseudoValue, receiver)
                    }
                }
                is TransientReceiver -> {
                    // Do nothing
                }
                else -> {
                    throw IllegalArgumentException("Unknown receiver kind: " + receiver)
                }
            }

            return receiverValues
        }

        private fun generateValueArgument(
                valueArgument: ValueArgument,
                parameterDescriptor: ValueParameterDescriptor,
                parameterValuesArg: SmartFMap<PseudoValue, ValueParameterDescriptor>
        ): SmartFMap<PseudoValue, ValueParameterDescriptor> {
            var parameterValues = parameterValuesArg
            val expression = valueArgument.getArgumentExpression()
            if (expression != null) {
                if (!valueArgument.isExternal()) {
                    generateInstructions(expression)
                }

                val argValue = getBoundOrUnreachableValue(expression)
                if (argValue != null) {
                    parameterValues = parameterValues.plus(argValue, parameterDescriptor)
                }
            }
            return parameterValues
        }
    }
}