/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ir.isPure
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.at
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irNot
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.ir2wasm.erasedUpperBound
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.backend.js.utils.findUnitGetInstanceFunction
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid


class WasmTypeOperatorLowering(val context: WasmBackendContext) : FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(WasmBaseTypeOperatorTransformer(context))
    }
}

class WasmBaseTypeOperatorTransformer(val context: WasmBackendContext) : IrElementTransformerVoidWithContext() {
    private val symbols = context.wasmSymbols
    private val builtIns = context.irBuiltIns

    private lateinit var builder: DeclarationIrBuilder

    override fun visitTypeOperator(expression: IrTypeOperatorCall): IrExpression {
        super.visitTypeOperator(expression)
        builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(expression)

        return when (expression.operator) {
            IrTypeOperator.IMPLICIT_CAST -> lowerImplicitCast(expression)
            IrTypeOperator.IMPLICIT_DYNAMIC_CAST -> error("Dynamic casts are not supported in Wasm backend")
            IrTypeOperator.IMPLICIT_COERCION_TO_UNIT -> expression
            IrTypeOperator.IMPLICIT_INTEGER_COERCION -> lowerIntegerCoercion(expression)
            IrTypeOperator.IMPLICIT_NOTNULL -> lowerImplicitCast(expression)
            IrTypeOperator.INSTANCEOF -> lowerInstanceOf(expression, inverted = false)
            IrTypeOperator.NOT_INSTANCEOF -> lowerInstanceOf(expression, inverted = true)
            IrTypeOperator.CAST -> lowerCast(expression, isSafe = false)
            IrTypeOperator.SAFE_CAST -> lowerCast(expression, isSafe = true)
            IrTypeOperator.SAM_CONVERSION -> TODO("SAM conversion: ${expression.render()}")
            IrTypeOperator.REINTERPRET_CAST -> expression
        }
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        // Some IR passes, notable for-loops-lowering assumes implicit cast during variable initialization
        val initializer = declaration.initializer
        if (initializer != null &&
            initializer.type != declaration.type
        ) {
            builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol).at(declaration)
            declaration.initializer = narrowType(initializer.type, declaration.type, initializer)
        }

        return super.visitVariable(declaration)
    }

    private fun lowerInstanceOf(
        expression: IrTypeOperatorCall,
        inverted: Boolean
    ): IrExpression {
        return builder.irComposite(resultType = builtIns.booleanType) {
            val argument = cacheValue(expression.argument)
            val check = generateTypeCheck(argument, expression.typeOperand)
            if (inverted) {
                +builder.irNot(check)
            } else {
                +check
            }
        }
    }

    private fun IrBlockBuilder.cacheValue(value: IrExpression): () -> IrExpression {
        if (value.isPure(true) && value.isTrivial()) {
            return { value.deepCopyWithSymbols() }
        }
        val tmpVal = createTmpVariable(value)
        return { builder.irGet(tmpVal) }
    }

    private fun IrType.isInlined(): Boolean =
        context.inlineClassesUtils.isTypeInlined(this)

    private val IrType.erasedType: IrType
        get() = this.erasedUpperBound?.defaultType ?: builtIns.anyType

    private fun generateTypeCheck(
        valueProvider: () -> IrExpression,
        toType: IrType
    ): IrExpression {
        val toNotNullable = toType.makeNotNull()
        val valueInstance: IrExpression = valueProvider()
        val fromType = valueInstance.type

        // Inlined values have no type information on runtime.
        // But since they are final we can compute type checks on compile time.
        if (fromType.isInlined()) {
            val result = fromType.erasedType.isSubtypeOf(toType.erasedType, context.typeSystem)
            return builder.irBoolean(result)
        }

        val instanceCheck = generateTypeCheckNonNull(valueInstance, toNotNullable)
        val isFromNullable = valueInstance.type.isNullable()
        val isToNullable = toType.isNullable()

        return when {
            !isFromNullable -> instanceCheck

            else ->
                builder.irIfThenElse(
                    type = builtIns.booleanType,
                    condition = builder.irEqualsNull(valueProvider()),
                    thenPart = builder.irBoolean(isToNullable),
                    elsePart = instanceCheck
                )
        }
    }

    private fun lowerIntegerCoercion(expression: IrTypeOperatorCall): IrExpression =
        when (expression.typeOperand) {
            builtIns.byteType,
            builtIns.shortType ->
                expression.argument

            builtIns.longType ->
                builder.irCall(symbols.intToLong).apply {
                    putValueArgument(0, expression.argument)
                }

            else -> error("Unreachable execution (coercion to non-Integer type")
        }

    private fun generateTypeCheckNonNull(argument: IrExpression, toType: IrType): IrExpression {
        assert(!toType.isMarkedNullable())
        return when {
            toType.isNothing() -> builder.irFalse()
            toType.isTypeParameter() -> generateTypeCheckWithTypeParameter(argument, toType)
            toType.isInterface() -> generateIsInterface(argument, toType)
            else -> generateIsSubClass(argument, toType)
        }
    }

    private fun narrowType(fromType: IrType, toType: IrType, value: IrExpression): IrExpression {
        if (fromType == toType) return value

        if (toType == builtIns.nothingNType) {
            return builder.irComposite(resultType = builtIns.nothingNType) {
                +value
                +builder.irNull()
            }
        }

        // Workaround for the bug in frontend.
        // In code like this:
        //    val p: Int = try {
        //        1
        //    } catch (e: Exception) {
        //        throw RuntimeException()
        //    }
        // Frontend will emit IMPLICIT_CAST from Unit into Int. Such code can't be correctly compiled to wasm as it is.
        // This supposed to only happen in the unreachable code so we emit unreachable intrinsic.
        if (fromType == builtIns.unitType && toType !in listOf(builtIns.unitType, builtIns.anyType, builtIns.anyNType)) {
            return builder.irComposite(resultType = toType) {
                +value
                +builder.irCall(symbols.wasmUnreachable)
            }
        }

        // Handled by autoboxing transformer
        if (toType.isInlined() && !fromType.isInlined()) {
            return builder.irCall(
                symbols.unboxIntrinsic,
                toType,
                typeArguments = listOf(fromType, toType)
            ).also {
                it.putValueArgument(0, value)
            }
        }

        if (!toType.isInlined() && fromType.isInlined()) {
            return builder.irCall(
                symbols.boxIntrinsic,
                toType,
                typeArguments = listOf(fromType, toType)
            ).also {
                it.putValueArgument(0, value)
            }
        }

        if (fromType.erasedType.isSubtypeOf(toType.erasedType, context.typeSystem)) {
            return value
        }
        if (toType.isNothing()) {
            return value
        }


        // Ref casts traps on null (https://github.com/WebAssembly/gc/issues/152)
        // Handling null manually
        if (toType.isNullable() && fromType.isNullable()) {
            return builder.irComposite {
                val cachedValue = cacheValue(value)
                +builder.irIfNull(
                    type = toType,
                    subject = cachedValue(),
                    thenPart = builder.irNull(toType),
                    elsePart = builder.irCall(symbols.wasmRefCast, type = toType).apply {
                        putTypeArgument(0, toType)
                        putValueArgument(0, cachedValue())
                    }
                )
            }
        }

        return builder.irCall(symbols.wasmRefCast, type = toType).apply {
            putTypeArgument(0, toType)
            putValueArgument(0, value)
        }
    }

    private fun lowerCast(
        expression: IrTypeOperatorCall,
        isSafe: Boolean
    ): IrExpression {
        val toType = expression.typeOperand
        val fromType = expression.argument.type

        if (fromType.erasedType.isSubtypeOf(expression.type.erasedType, context.typeSystem)) {
            return narrowType(fromType, expression.type, expression.argument)
        }

        val failResult = if (isSafe) {
            builder.irNull()
        } else {
            builder.irCall(context.ir.symbols.throwTypeCastException)
        }

        return builder.irComposite(resultType = expression.type) {
            val argument = cacheValue(expression.argument)
            val narrowArg = narrowType(fromType, expression.type, argument())
            val check = generateTypeCheck(argument, toType)
            if (check is IrConst<*>) {
                val value = check.value as Boolean
                if (value) {
                    +narrowArg
                } else {
                    +failResult
                }
            } else {
                +builder.irIfThenElse(
                    type = expression.type,
                    condition = check,
                    thenPart = narrowArg,
                    elsePart = failResult
                )
            }
        }
    }

    private fun lowerImplicitCast(expression: IrTypeOperatorCall): IrExpression =
        narrowType(
            fromType = expression.argument.type,
            toType = expression.typeOperand,
            value = expression.argument
        )

    private fun generateTypeCheckWithTypeParameter(argument: IrExpression, toType: IrType): IrExpression {
        val typeParameter = toType.classifierOrNull?.owner as? IrTypeParameter
            ?: error("expected type parameter, but got $toType")

        return typeParameter.superTypes.fold(builder.irTrue() as IrExpression) { r, t ->
            val check = generateTypeCheckNonNull(argument.shallowCopy(), t.makeNotNull())
            builder.irCall(symbols.booleanAnd).apply {
                putValueArgument(0, r)
                putValueArgument(1, check)
            }
        }
    }

    private fun generateIsInterface(argument: IrExpression, toType: IrType): IrExpression {
        val interfaceId = builder.irCall(symbols.wasmInterfaceId).apply {
            putTypeArgument(0, toType)
        }
        return builder.irCall(symbols.isInterface).apply {
            putValueArgument(0, argument)
            putValueArgument(1, interfaceId)
        }
    }

    private fun generateIsSubClass(argument: IrExpression, toType: IrType): IrExpression {
        val fromType = argument.type
        val fromTypeErased = fromType.erasedType
        val toTypeErased = toType.erasedType
        if (fromTypeErased.isSubtypeOfClass(toTypeErased.classOrNull!!)) {
            return builder.irTrue()
        }
        if (!toTypeErased.isSubtypeOfClass(fromTypeErased.classOrNull!!)) {
            return builder.irFalse()
        }

        return builder.irCall(symbols.refTest).apply {
            putValueArgument(0, argument)
            putTypeArgument(0, toType)
        }
    }
}
