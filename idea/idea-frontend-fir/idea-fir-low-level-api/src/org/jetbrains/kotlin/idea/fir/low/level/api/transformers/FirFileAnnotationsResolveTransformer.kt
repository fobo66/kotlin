package org.jetbrains.kotlin.idea.fir.low.level.api.transformers

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.resolved
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.resolve.ResolutionMode
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirBodyResolveTransformer
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirTowerDataContextCollector
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.ImplicitBodyResolveComputationSession
import org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.createReturnTypeCalculatorForIDE
import org.jetbrains.kotlin.idea.fir.low.level.api.FirPhaseRunner
import org.jetbrains.kotlin.idea.fir.low.level.api.element.builder.FirIdeDesignatedImpliciteTypesBodyResolveTransformerForReturnTypeCalculator

internal class FirFileAnnotationsResolveTransformer(
    private val firFile: FirFile,
    private val annotations: List<FirAnnotation>,
    session: FirSession,
    scopeSession: ScopeSession,
    implicitBodyResolveComputationSession: ImplicitBodyResolveComputationSession = ImplicitBodyResolveComputationSession(),
    firTowerDataContextCollector: FirTowerDataContextCollector? = null,
) : FirBodyResolveTransformer(
    session = session,
    phase = FirResolvePhase.BODY_RESOLVE,
    implicitTypeOnly = false,
    scopeSession = scopeSession,
    returnTypeCalculator = createReturnTypeCalculatorForIDE(
        session,
        scopeSession,
        implicitBodyResolveComputationSession,
        ::FirIdeDesignatedImpliciteTypesBodyResolveTransformerForReturnTypeCalculator
    ),
    firTowerDataContextCollector = firTowerDataContextCollector
), FirLazyTransformerForIDE {

    override fun transformDeclarationContent(declaration: FirDeclaration, data: ResolutionMode): FirDeclaration {
        require(declaration is FirFile) { "Unexpected declaration ${declaration::class.simpleName}" }
        annotations.forEach {
            if (!it.resolved) {
                it.visitNoTransform(this, data)
            }
        }
        return declaration
    }

    override fun transformDeclaration(phaseRunner: FirPhaseRunner) {
        if (annotations.all { it.resolved }) return
        check(firFile.resolvePhase >= FirResolvePhase.IMPORTS) { "Invalid file resolve phase ${firFile.resolvePhase}" }

        firFile.accept(this, ResolutionMode.ContextDependent)
        check(annotations.all { it.resolved }) {
            "Annotation was not resolved"
        }
    }

    override fun ensureResolved(declaration: FirDeclaration) = error("Not implemented")
}
