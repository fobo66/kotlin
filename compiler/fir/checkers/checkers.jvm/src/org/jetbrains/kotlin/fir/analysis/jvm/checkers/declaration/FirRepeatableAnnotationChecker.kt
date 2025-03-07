/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.jvm.checkers.declaration

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.checkers.*
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirAnnotatedDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.diagnostics.withSuppressedDiagnostics
import org.jetbrains.kotlin.fir.analysis.jvm.checkers.isJvm6
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.java.JavaSymbolProvider
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.resolve.defaultType
import org.jetbrains.kotlin.fir.resolve.symbolProvider
import org.jetbrains.kotlin.fir.scopes.getSingleClassifier
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

object FirRepeatableAnnotationChecker : FirAnnotatedDeclarationChecker() {
    private val REPEATABLE_PARAMETER_NAME = Name.identifier("value")
    private val JAVA_REPEATABLE_ANNOTATION = ClassId.fromString("java/lang/annotation/Repeatable")
    private val REPEATABLE_ANNOTATION_CONTAINER_NAME = Name.identifier(JvmAbi.REPEATABLE_ANNOTATION_CONTAINER_NAME)

    override fun check(declaration: FirAnnotatedDeclaration, context: CheckerContext, reporter: DiagnosticReporter) {
        val annotationsMap = hashMapOf<ConeKotlinType, MutableList<AnnotationUseSiteTarget?>>()

        val session = context.session
        val annotations = declaration.annotations
        for (annotation in annotations) {
            val classId = annotation.classId ?: continue
            val annotationClassId = annotation.toAnnotationClassId() ?: continue
            if (annotationClassId.isLocal) continue
            val annotationClass = session.symbolProvider.getClassLikeSymbolByClassId(annotationClassId) ?: continue

            val useSiteTarget = annotation.useSiteTarget
            val existingTargetsForAnnotation = annotationsMap.getOrPut(annotation.annotationTypeRef.coneType) { arrayListOf() }
            val duplicateAnnotation = useSiteTarget in existingTargetsForAnnotation ||
                    existingTargetsForAnnotation.any { (it == null) != (useSiteTarget == null) }

            if (duplicateAnnotation &&
                annotationClass.containsRepeatableAnnotation(session) &&
                annotationClass.getAnnotationRetention() != AnnotationRetention.SOURCE
            ) {
                if (context.isJvm6()) {
                    reporter.reportOn(annotation.source, FirJvmErrors.REPEATED_ANNOTATION_TARGET6, context)
                } else if (session.languageVersionSettings.supportsFeature(LanguageFeature.RepeatableAnnotations)) {
                    // It's not allowed to have both a repeated annotation (applied more than once) and its container
                    // on the same element.
                    // See https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-9.7.5.
                    val explicitContainer = annotationClass.resolveContainerAnnotation()
                    if (explicitContainer != null && annotations.any { it.classId == explicitContainer }) {
                        reporter.reportOn(
                            annotation.source,
                            FirJvmErrors.REPEATED_ANNOTATION_WITH_CONTAINER,
                            classId,
                            explicitContainer,
                            context
                        )
                    }
                } else {
                    reporter.reportOn(annotation.source, FirJvmErrors.NON_SOURCE_REPEATED_ANNOTATION, context)
                }
            }

            existingTargetsForAnnotation.add(useSiteTarget)
        }

        if (declaration is FirRegularClass) {
            val javaRepeatable = annotations.find { it.classId == JAVA_REPEATABLE_ANNOTATION }
            if (javaRepeatable != null) {
                withSuppressedDiagnostics(javaRepeatable, context) {
                    checkJavaRepeatableAnnotationDeclaration(javaRepeatable, declaration, context, reporter)
                }
            } else {
                val kotlinRepeatable = annotations.find { it.classId == StandardNames.FqNames.repeatableClassId }
                if (kotlinRepeatable != null) {
                    withSuppressedDiagnostics(kotlinRepeatable, context) {
                        checkKotlinRepeatableAnnotationDeclaration(kotlinRepeatable, declaration, context, reporter)
                    }
                }
            }
        }
    }

    private fun FirClassLikeSymbol<*>.resolveContainerAnnotation(): ClassId? {
        val repeatableAnnotation =
            getAnnotationByClassId(StandardNames.FqNames.repeatableClassId) ?: getAnnotationByClassId(JAVA_REPEATABLE_ANNOTATION) ?: return null
        return repeatableAnnotation.resolveContainerAnnotation()
    }

    private fun FirAnnotation.resolveContainerAnnotation(): ClassId? {
        val value = findArgumentByName(REPEATABLE_PARAMETER_NAME) ?: return null
        val classCallArgument = (value as? FirGetClassCall)?.argument ?: return null
        if (classCallArgument is FirResolvedQualifier) {
            return classCallArgument.classId
        } else if (classCallArgument is FirClassReferenceExpression) {
            val type = classCallArgument.classTypeRef.coneType.lowerBoundIfFlexible() as? ConeClassLikeType ?: return null
            return type.lookupTag.classId
        }
        return null
    }

    private fun checkJavaRepeatableAnnotationDeclaration(
        javaRepeatable: FirAnnotation,
        annotationClass: FirRegularClass,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val containerClassId = javaRepeatable.resolveContainerAnnotation() ?: return
        val containerClassSymbol = context.session.symbolProvider.getClassLikeSymbolByClassId(containerClassId) as? FirRegularClassSymbol ?: return

        checkRepeatableAnnotationContainer(annotationClass, containerClassSymbol, javaRepeatable.source, context, reporter)
    }

    private fun checkKotlinRepeatableAnnotationDeclaration(
        kotlinRepeatable: FirAnnotation,
        declaration: FirRegularClass,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val unsubsitutedScope = declaration.unsubstitutedScope(context)
        if (unsubsitutedScope.getSingleClassifier(REPEATABLE_ANNOTATION_CONTAINER_NAME) != null) {
            reporter.reportOn(kotlinRepeatable.source, FirJvmErrors.REPEATABLE_ANNOTATION_HAS_NESTED_CLASS_NAMED_CONTAINER, context)
        }
    }

    private fun checkRepeatableAnnotationContainer(
        annotationClass: FirRegularClass,
        containerClass: FirRegularClassSymbol,
        annotationSource: FirSourceElement?,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        checkContainerParameters(containerClass, annotationClass, annotationSource, context, reporter)
        checkContainerRetention(containerClass, annotationClass, annotationSource, context, reporter)
        checkContainerTarget(containerClass, annotationClass, annotationSource, context, reporter)
    }

    private fun checkContainerParameters(
        containerClass: FirRegularClassSymbol,
        annotationClass: FirRegularClass,
        annotationSource: FirSourceElement?,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val containerCtor =
            containerClass.declarationSymbols.find { it is FirConstructorSymbol && it.isPrimary } as? FirConstructorSymbol
                ?: return

        val valueParameterSymbols = containerCtor.valueParameterSymbols
        val parameterName = JavaSymbolProvider.VALUE_METHOD_NAME
        val value = valueParameterSymbols.find { it.name == parameterName }
        if (value == null || !value.resolvedReturnTypeRef.isArrayType ||
            value.resolvedReturnTypeRef.type.typeArguments.single().type != annotationClass.defaultType()
        ) {
            reporter.reportOn(
                annotationSource,
                FirJvmErrors.REPEATABLE_CONTAINER_MUST_HAVE_VALUE_ARRAY,
                containerClass.classId,
                annotationClass.classId,
                context
            )
            return
        }

        val otherNonDefault = valueParameterSymbols.find { it.name != parameterName && !it.hasDefaultValue }
        if (otherNonDefault != null) {
            reporter.reportOn(
                annotationSource,
                FirJvmErrors.REPEATABLE_CONTAINER_HAS_NON_DEFAULT_PARAMETER,
                containerClass.classId,
                otherNonDefault.name,
                context
            )
            return
        }
    }

    private fun checkContainerRetention(
        containerClass: FirRegularClassSymbol,
        annotationClass: FirRegularClass,
        annotationSource: FirSourceElement?,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val annotationRetention = annotationClass.symbol.getAnnotationRetention()
        val containerRetention = containerClass.getAnnotationRetention()
        if (containerRetention < annotationRetention) {
            reporter.reportOn(
                annotationSource,
                FirJvmErrors.REPEATABLE_CONTAINER_HAS_SHORTER_RETENTION,
                containerClass.classId,
                containerRetention.name,
                annotationClass.classId,
                annotationRetention.name,
                context
            )
        }
    }

    private fun checkContainerTarget(
        containerClass: FirRegularClassSymbol,
        annotationClass: FirRegularClass,
        annotationSource: FirSourceElement?,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val annotationTargets = annotationClass.getAllowedAnnotationTargets()
        val containerTargets = containerClass.getAllowedAnnotationTargets()

        // See https://docs.oracle.com/javase/specs/jls/se16/html/jls-9.html#jls-9.6.3.
        // (TBH, the rules about TYPE/TYPE_USE and TYPE_PARAMETER/TYPE_USE don't seem to make a lot of sense, but it's JLS
        // so we better obey it for full interop with the Java language and reflection.)
        for (target in containerTargets) {
            val ok = when (target) {
                in annotationTargets -> true
                KotlinTarget.ANNOTATION_CLASS ->
                    KotlinTarget.CLASS in annotationTargets ||
                            KotlinTarget.TYPE in annotationTargets
                KotlinTarget.CLASS ->
                    KotlinTarget.TYPE in annotationTargets
                KotlinTarget.TYPE_PARAMETER ->
                    KotlinTarget.TYPE in annotationTargets
                else -> false
            }
            if (!ok) {
                reporter.reportOn(
                    annotationSource,
                    FirJvmErrors.REPEATABLE_CONTAINER_TARGET_SET_NOT_A_SUBSET,
                    containerClass.classId,
                    annotationClass.classId,
                    context
                )
                return
            }
        }
    }
}
