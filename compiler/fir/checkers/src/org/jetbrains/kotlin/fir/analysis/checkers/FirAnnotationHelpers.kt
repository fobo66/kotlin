/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers

import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.descriptors.annotations.KotlinTarget
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.context.findClosest
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirPrimaryConstructor
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.ensureResolved
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirErrorTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.UseSiteTargetsList

private val RETENTION_PARAMETER_NAME = Name.identifier("value")
private val TARGET_PARAMETER_NAME = Name.identifier("allowedTargets")
private val JAVA_REPEATABLE_ANNOTATION = FqName("java.lang.annotation.Repeatable")
private val JAVA_REPEATABLE_ANNOTATION_CLASS_ID = ClassId.topLevel(JAVA_REPEATABLE_ANNOTATION)
private val JVM_REPEATABLE_ANNOTATION_CLASS_ID = ClassId.fromString("kotlin/jvm/JvmRepeatable")

@OptIn(SymbolInternals::class)
fun FirAnnotation.getRetention(session: FirSession): AnnotationRetention {
    val annotationClassSymbol =
        (this.annotationTypeRef.coneType as? ConeClassLikeType)?.lookupTag?.toSymbol(session) as? FirRegularClassSymbol
            ?: return AnnotationRetention.RUNTIME
    annotationClassSymbol.ensureResolved(FirResolvePhase.BODY_RESOLVE)
    val annotationClass = annotationClassSymbol.fir
    return annotationClass.getRetention()
}

fun FirRegularClass.getRetention(): AnnotationRetention {
    return getRetentionAnnotation()?.getRetention() ?: AnnotationRetention.RUNTIME
}

fun FirAnnotation.getRetention(): AnnotationRetention {
    val retentionArgument = findArgumentByName(RETENTION_PARAMETER_NAME) as? FirQualifiedAccessExpression
        ?: return AnnotationRetention.RUNTIME
    val retentionName = (retentionArgument.calleeReference as? FirResolvedNamedReference)?.name?.asString()
        ?: return AnnotationRetention.RUNTIME
    return AnnotationRetention.values().firstOrNull { it.name == retentionName } ?: AnnotationRetention.RUNTIME
}

private val defaultAnnotationTargets = KotlinTarget.DEFAULT_TARGET_SET

fun FirAnnotation.getAllowedAnnotationTargets(session: FirSession): Set<KotlinTarget> {
    if (annotationTypeRef is FirErrorTypeRef) return KotlinTarget.values().toSet()
    val annotationClassSymbol = (this.annotationTypeRef.coneType as? ConeClassLikeType)
        ?.fullyExpandedType(session)?.lookupTag?.toSymbol(session) ?: return defaultAnnotationTargets
    annotationClassSymbol.ensureResolved(FirResolvePhase.BODY_RESOLVE)
    return annotationClassSymbol.getAllowedAnnotationTargets()
}

fun FirRegularClass.getAllowedAnnotationTargets(): Set<KotlinTarget> {
    return symbol.getAllowedAnnotationTargets()
}

fun FirClassLikeSymbol<*>.getAllowedAnnotationTargets(): Set<KotlinTarget> {
    val targetAnnotation = getTargetAnnotation() ?: return defaultAnnotationTargets
    val arguments = targetAnnotation.findArgumentByName(TARGET_PARAMETER_NAME)?.unfoldArrayOrVararg().orEmpty()

    return arguments.mapNotNullTo(mutableSetOf()) { argument ->
        val targetExpression = argument as? FirQualifiedAccessExpression
        val targetName = (targetExpression?.calleeReference as? FirResolvedNamedReference)?.name?.asString() ?: return@mapNotNullTo null
        KotlinTarget.values().firstOrNull { target -> target.name == targetName }
    }
}

fun FirAnnotatedDeclaration.getRetentionAnnotation(): FirAnnotation? {
    return getAnnotationByClassId(StandardNames.FqNames.retentionClassId)
}

fun FirAnnotatedDeclaration.getTargetAnnotation(): FirAnnotation? {
    return getAnnotationByClassId(StandardNames.FqNames.targetClassId)
}

fun FirClassLikeSymbol<*>.getTargetAnnotation(): FirAnnotation? {
    return getAnnotationByClassId(StandardNames.FqNames.targetClassId)
}

fun FirExpression.extractClassesFromArgument(): List<FirRegularClassSymbol> {
    return unfoldArrayOrVararg().mapNotNull {
        if (it !is FirGetClassCall) return@mapNotNull null
        val qualifier = it.argument as? FirResolvedQualifier ?: return@mapNotNull null
        qualifier.symbol as? FirRegularClassSymbol
    }
}

fun checkRepeatedAnnotation(
    useSiteTarget: AnnotationUseSiteTarget?,
    existingTargetsForAnnotation: MutableList<AnnotationUseSiteTarget?>,
    annotation: FirAnnotation,
    context: CheckerContext,
    reporter: DiagnosticReporter,
) {
    val duplicated = useSiteTarget in existingTargetsForAnnotation
            || existingTargetsForAnnotation.any { (it == null) != (useSiteTarget == null) }
    if (duplicated && !annotation.isRepeatable(context.session) && annotation.source?.kind !is FirFakeSourceElementKind) {
        reporter.reportOn(annotation.source, FirErrors.REPEATED_ANNOTATION, context)
    }
}

fun FirAnnotation.isRepeatable(session: FirSession): Boolean {
    val annotationClassId = this.toAnnotationClassId() ?: return false
    if (annotationClassId.isLocal) return false
    val annotationClass = session.symbolProvider.getClassLikeSymbolByClassId(annotationClassId) ?: return false

    return annotationClass.containsRepeatableAnnotation(session)
}

fun FirClassLikeSymbol<*>.containsRepeatableAnnotation(session: FirSession): Boolean {
    if (getAnnotationByClassId(StandardNames.FqNames.repeatableClassId) != null) return true
    if (getAnnotationByClassId(JAVA_REPEATABLE_ANNOTATION_CLASS_ID) != null ||
        getAnnotationByClassId(JVM_REPEATABLE_ANNOTATION_CLASS_ID) != null
    ) {
        return session.languageVersionSettings.supportsFeature(LanguageFeature.RepeatableAnnotations) ||
                getAnnotationRetention() == AnnotationRetention.SOURCE && origin == FirDeclarationOrigin.Java
    }
    return false
}

fun FirClassLikeSymbol<*>.getAnnotationRetention(): AnnotationRetention {
    return getAnnotationByClassId(StandardNames.FqNames.retentionClassId)?.getRetention() ?: AnnotationRetention.RUNTIME
}

fun FirAnnotationContainer.getDefaultUseSiteTarget(
    annotation: FirAnnotation,
    context: CheckerContext
): AnnotationUseSiteTarget? {
    return getImplicitUseSiteTargetList(context).firstOrNull {
        KotlinTarget.USE_SITE_MAPPING[it] in annotation.getAllowedAnnotationTargets(context.session)
    }
}

fun FirAnnotationContainer.getImplicitUseSiteTargetList(context: CheckerContext): List<AnnotationUseSiteTarget> {
    return when (this) {
        is FirValueParameter -> {
            return if (context.findClosest<FirDeclaration>() is FirPrimaryConstructor)
                UseSiteTargetsList.T_CONSTRUCTOR_PARAMETER
            else
                emptyList()
        }
        is FirProperty ->
            if (!isLocal) UseSiteTargetsList.T_PROPERTY else emptyList()
        is FirPropertyAccessor ->
            if (isGetter) listOf(AnnotationUseSiteTarget.PROPERTY_GETTER) else listOf(AnnotationUseSiteTarget.PROPERTY_SETTER)
        else ->
            emptyList()
    }
}

private fun FirExpression.unfoldArrayOrVararg(): List<FirExpression> {
    return when (this) {
        is FirVarargArgumentsExpression -> arguments
        is FirArrayOfCall -> arguments
        else -> return emptyList()
    }
}

