/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.lightTree.converter

import com.intellij.lang.LighterASTNode
import com.intellij.psi.TokenType
import com.intellij.util.diff.FlyweightCapableTreeStructure
import org.jetbrains.kotlin.KtNodeTypes.*
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.StandardNames.DEFAULT_VALUE_PARAMETER
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.builder.*
import org.jetbrains.kotlin.fir.contracts.FirContractDescription
import org.jetbrains.kotlin.fir.contracts.builder.buildRawContractDescription
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.builder.*
import org.jetbrains.kotlin.fir.declarations.impl.*
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.diagnostics.ConeSimpleDiagnostic
import org.jetbrains.kotlin.fir.diagnostics.DiagnosticKind
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.expressions.builder.*
import org.jetbrains.kotlin.fir.expressions.impl.FirEmptyAnnotationArgumentMapping
import org.jetbrains.kotlin.fir.expressions.impl.FirSingleExpressionBlock
import org.jetbrains.kotlin.fir.lightTree.LightTree2Fir
import org.jetbrains.kotlin.fir.lightTree.fir.*
import org.jetbrains.kotlin.fir.lightTree.fir.modifier.Modifier
import org.jetbrains.kotlin.fir.lightTree.fir.modifier.TypeModifier
import org.jetbrains.kotlin.fir.lightTree.fir.modifier.TypeParameterModifier
import org.jetbrains.kotlin.fir.lightTree.fir.modifier.TypeProjectionModifier
import org.jetbrains.kotlin.fir.references.builder.buildExplicitSuperReference
import org.jetbrains.kotlin.fir.references.builder.buildExplicitThisReference
import org.jetbrains.kotlin.fir.references.builder.buildSimpleNamedReference
import org.jetbrains.kotlin.fir.references.impl.FirReferencePlaceholderForResolvedAnnotations
import org.jetbrains.kotlin.fir.scopes.FirScopeProvider
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.builder.*
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.impl.FirQualifierPartImpl
import org.jetbrains.kotlin.fir.types.impl.FirTypeArgumentListImpl
import org.jetbrains.kotlin.lexer.KtModifierKeywordToken
import org.jetbrains.kotlin.lexer.KtTokens.*
import org.jetbrains.kotlin.name.*
import org.jetbrains.kotlin.utils.addToStdlib.runIf

class DeclarationsConverter(
    session: FirSession,
    private val baseScopeProvider: FirScopeProvider,
    tree: FlyweightCapableTreeStructure<LighterASTNode>,
    offset: Int = 0,
    context: Context<LighterASTNode> = Context()
) : BaseConverter(session, tree, context) {
    @set:PrivateForInline
    override var offset: Int = offset

    @OptIn(PrivateForInline::class)
    inline fun <R> withOffset(newOffset: Int, block: () -> R): R {
        val oldOffset = offset
        offset = newOffset
        return try {
            block()
        } finally {
            offset = oldOffset
        }
    }

    private val expressionConverter = ExpressionsConverter(session, tree, this, context)

    /**
     * [org.jetbrains.kotlin.parsing.KotlinParsing.parseFile]
     * [org.jetbrains.kotlin.parsing.KotlinParsing.parsePreamble]
     */
    fun convertFile(file: LighterASTNode, fileName: String = ""): FirFile {
        if (file.tokenType != KT_FILE) {
            //TODO throw error
            throw Exception()
        }

        val fileAnnotationList = mutableListOf<FirAnnotation>()
        val importList = mutableListOf<FirImport>()
        val firDeclarationList = mutableListOf<FirDeclaration>()
        context.packageFqName = FqName.ROOT
        var packageDirective: FirPackageDirective? = null
        file.forEachChildren {
            when (it.tokenType) {
                FILE_ANNOTATION_LIST -> fileAnnotationList += convertFileAnnotationList(it)
                PACKAGE_DIRECTIVE -> {
                    packageDirective = convertPackageDirective(it).also { context.packageFqName = it.packageFqName }
                }
                IMPORT_LIST -> importList += convertImportDirectives(it)
                CLASS -> firDeclarationList += convertClass(it)
                FUN -> firDeclarationList += convertFunctionDeclaration(it) as FirDeclaration
                PROPERTY -> firDeclarationList += convertPropertyDeclaration(it)
                TYPEALIAS -> firDeclarationList += convertTypeAlias(it)
                OBJECT_DECLARATION -> firDeclarationList += convertClass(it)
                SCRIPT -> {
                    // TODO: scripts aren't supported yet
                }
            }
        }

        return buildFile {
            source = file.toFirSourceElement()
            origin = FirDeclarationOrigin.Source
            moduleData = baseModuleData
            name = fileName
            this.packageDirective = packageDirective ?: buildPackageDirective { packageFqName = context.packageFqName }
            annotations += fileAnnotationList
            imports += importList
            declarations += firDeclarationList
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseBlockExpression
     */
    fun convertBlockExpression(block: LighterASTNode): FirBlock {
        return convertBlockExpressionWithoutBuilding(block).build()
    }

    fun convertBlockExpressionWithoutBuilding(block: LighterASTNode): FirBlockBuilder {
        val firStatements = block.forEachChildrenReturnList<FirStatement> { node, container ->
            when (node.tokenType) {
                CLASS, OBJECT_DECLARATION -> container += convertClass(node) as FirStatement
                FUN -> container += convertFunctionDeclaration(node)
                PROPERTY -> container += convertPropertyDeclaration(node) as FirStatement
                DESTRUCTURING_DECLARATION -> container += convertDestructingDeclaration(node).toFirDestructingDeclaration(baseModuleData)
                TYPEALIAS -> container += convertTypeAlias(node) as FirStatement
                CLASS_INITIALIZER -> container += convertAnonymousInitializer(node) as FirStatement
                else -> if (node.isExpression()) container += expressionConverter.getAsFirExpression<FirStatement>(node)
            }
        }
        return FirBlockBuilder().apply {
            source = block.toFirSourceElement()
            firStatements.forEach { firStatement ->
                val isForLoopBlock = firStatement is FirBlock && firStatement.source?.kind == FirFakeSourceElementKind.DesugaredForLoop
                if (firStatement !is FirBlock || isForLoopBlock || firStatement.annotations.isNotEmpty()) {
                    statements += firStatement
                } else {
                    statements += firStatement.statements
                }
            }
        }
    }

    /*****    PREAMBLE    *****/
    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parsePackageName
     */
    private fun convertPackageDirective(packageNode: LighterASTNode): FirPackageDirective {
        var packageName: FqName = FqName.ROOT
        packageNode.forEachChildren {
            when (it.tokenType) {
                //TODO separate logic for both expression types
                DOT_QUALIFIED_EXPRESSION, REFERENCE_EXPRESSION -> packageName = FqName(it.getAsStringWithoutBacktick())
            }
        }
        return buildPackageDirective {
            packageFqName = packageName
            source = packageNode.toFirSourceElement()
        }
    }

    private fun convertImportAlias(importAlias: LighterASTNode): Pair<String, FirSourceElement>? {
        importAlias.forEachChildren {
            when (it.tokenType) {
                IDENTIFIER -> return Pair(it.asText, it.toFirSourceElement())
            }
        }

        return null
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseImportDirective
     */
    private fun convertImportDirective(importDirective: LighterASTNode): FirImport {
        var importedFqName: FqName? = null
        var isAllUnder = false
        var aliasName: String? = null
        var aliasSource: FirSourceElement? = null
        importDirective.forEachChildren {
            when (it.tokenType) {
                DOT_QUALIFIED_EXPRESSION, REFERENCE_EXPRESSION -> importedFqName = FqName(it.asText)
                MUL -> isAllUnder = true
                IMPORT_ALIAS -> {
                    val importAlias = convertImportAlias(it)
                    if (importAlias != null) {
                        aliasName = importAlias.first
                        aliasSource = importAlias.second
                    }
                }
            }
        }

        return buildImport {
            source = importDirective.toFirSourceElement()
            this.importedFqName = importedFqName
            this.isAllUnder = isAllUnder
            this.aliasName = aliasName?.let { Name.identifier(it) }
            this.aliasSource = aliasSource
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseImportDirectives
     */
    private fun convertImportDirectives(importList: LighterASTNode): List<FirImport> {
        return importList.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                IMPORT_DIRECTIVE -> container += convertImportDirective(node)
            }
        }
    }

    /*****    MODIFIERS    *****/
    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseModifierList
     */
    private fun convertModifierList(modifiers: LighterASTNode, isInClass: Boolean = false): Modifier {
        val modifier = Modifier()
        modifiers.forEachChildren {
            when (it.tokenType) {
                ANNOTATION -> modifier.annotations += convertAnnotation(it)
                ANNOTATION_ENTRY -> modifier.annotations += convertAnnotationEntry(it)
                is KtModifierKeywordToken -> modifier.addModifier(it, isInClass)
            }
        }
        return modifier
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeModifierList
     */
    private fun convertTypeModifierList(modifiers: LighterASTNode): TypeModifier {
        val typeModifier = TypeModifier()
        modifiers.forEachChildren {
            when (it.tokenType) {
                ANNOTATION -> typeModifier.annotations += convertAnnotation(it)
                ANNOTATION_ENTRY -> typeModifier.annotations += convertAnnotationEntry(it)
                is KtModifierKeywordToken -> typeModifier.addModifier(it)
            }
        }
        return typeModifier
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeArgumentModifierList
     */
    private fun convertTypeArgumentModifierList(modifiers: LighterASTNode): TypeProjectionModifier {
        val typeArgumentModifier = TypeProjectionModifier()
        modifiers.forEachChildren {
            when (it.tokenType) {
                ANNOTATION -> typeArgumentModifier.annotations += convertAnnotation(it)
                ANNOTATION_ENTRY -> typeArgumentModifier.annotations += convertAnnotationEntry(it)
                is KtModifierKeywordToken -> typeArgumentModifier.addModifier(it)
            }
        }
        return typeArgumentModifier
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeArgumentModifierList
     */
    private fun convertTypeParameterModifiers(modifiers: LighterASTNode): TypeParameterModifier {
        val modifier = TypeParameterModifier()
        modifiers.forEachChildren {
            when (it.tokenType) {
                ANNOTATION -> modifier.annotations += convertAnnotation(it)
                ANNOTATION_ENTRY -> modifier.annotations += convertAnnotationEntry(it)
                is KtModifierKeywordToken -> modifier.addModifier(it)
            }
        }
        return modifier
    }

    /*****    ANNOTATIONS    *****/
    /**
     * [org.jetbrains.kotlin.parsing.KotlinParsing.parseFileAnnotationList]
     */
    private fun convertFileAnnotationList(fileAnnotationList: LighterASTNode): List<FirAnnotation> {
        return fileAnnotationList.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                ANNOTATION -> container += convertAnnotation(node)
                ANNOTATION_ENTRY -> container += convertAnnotationEntry(node)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseAnnotationOrList
     */
    fun convertAnnotation(annotationNode: LighterASTNode): List<FirAnnotation> {
        var annotationTarget: AnnotationUseSiteTarget? = null
        return annotationNode.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                ANNOTATION_TARGET -> annotationTarget = convertAnnotationTarget(node)
                ANNOTATION_ENTRY -> container += convertAnnotationEntry(node, annotationTarget)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseAnnotationTarget
     */
    private fun convertAnnotationTarget(annotationUseSiteTarget: LighterASTNode): AnnotationUseSiteTarget {
        lateinit var annotationTarget: AnnotationUseSiteTarget
        annotationUseSiteTarget.forEachChildren {
            when (it.tokenType) {
                FIELD_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.FIELD
                FILE_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.FILE
                PROPERTY_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.PROPERTY
                GET_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.PROPERTY_GETTER
                SET_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.PROPERTY_SETTER
                RECEIVER_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.RECEIVER
                PARAM_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.CONSTRUCTOR_PARAMETER
                SETPARAM_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.SETTER_PARAMETER
                DELEGATE_KEYWORD -> annotationTarget = AnnotationUseSiteTarget.PROPERTY_DELEGATE_FIELD
            }
        }

        return annotationTarget
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseAnnotation
     * can be treated as unescapedAnnotation
     */
    fun convertAnnotationEntry(
        unescapedAnnotation: LighterASTNode,
        defaultAnnotationUseSiteTarget: AnnotationUseSiteTarget? = null
    ): FirAnnotation {
        var annotationUseSiteTarget: AnnotationUseSiteTarget? = null
        lateinit var constructorCalleePair: Pair<FirTypeRef, List<FirExpression>>
        unescapedAnnotation.forEachChildren {
            when (it.tokenType) {
                ANNOTATION_TARGET -> annotationUseSiteTarget = convertAnnotationTarget(it)
                CONSTRUCTOR_CALLEE -> constructorCalleePair = convertConstructorInvocation(unescapedAnnotation)
            }
        }
        val name = (constructorCalleePair.first as? FirUserTypeRef)?.qualifier?.last()?.name ?: Name.special("<no-annotation-name>")
        return buildAnnotationCall {
            source = unescapedAnnotation.toFirSourceElement()
            useSiteTarget = annotationUseSiteTarget ?: defaultAnnotationUseSiteTarget
            annotationTypeRef = constructorCalleePair.first
            calleeReference = buildSimpleNamedReference {
                source = unescapedAnnotation
                    .getChildNodeByType(CONSTRUCTOR_CALLEE)
                    ?.getChildNodeByType(TYPE_REFERENCE)
                    ?.getChildNodeByType(USER_TYPE)
                    ?.getChildNodeByType(REFERENCE_EXPRESSION)
                    ?.toFirSourceElement()
                this.name = name
            }
            extractArgumentsFrom(constructorCalleePair.second)
        }
    }

    private fun LighterASTNode.hasValueParameters(): Boolean {
        return getChildNodesByType(VALUE_PARAMETER_LIST).let {
            it.isNotEmpty() && it.first().getChildNodesByType(VALUE_PARAMETER).isNotEmpty()
        }
    }

    /*****    DECLARATIONS    *****/
    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseClassOrObject
     */
    private fun convertClass(classNode: LighterASTNode): FirDeclaration {
        var modifiers = Modifier()
        var classKind: ClassKind = ClassKind.CLASS //TODO
        var identifier: String? = null
        val firTypeParameters = mutableListOf<FirTypeParameter>()
        var primaryConstructor: LighterASTNode? = null
        val typeConstraints = mutableListOf<TypeConstraint>()
        var classBody: LighterASTNode? = null
        var superTypeList: LighterASTNode? = null
        var typeParameterList: LighterASTNode? = null

        classNode.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it, isInClass = true)
                CLASS_KEYWORD -> classKind = ClassKind.CLASS
                INTERFACE_KEYWORD -> classKind = ClassKind.INTERFACE
                OBJECT_KEYWORD -> classKind = ClassKind.OBJECT
                IDENTIFIER -> identifier = it.asText
                TYPE_PARAMETER_LIST -> typeParameterList = it
                PRIMARY_CONSTRUCTOR -> primaryConstructor = it
                SUPER_TYPE_LIST -> superTypeList = it
                TYPE_CONSTRAINT_LIST -> typeConstraints += convertTypeConstraints(it)
                CLASS_BODY -> classBody = it
            }
        }

        if (classKind == ClassKind.CLASS) {
            classKind = when {
                modifiers.isEnum() -> ClassKind.ENUM_CLASS
                modifiers.isAnnotation() -> ClassKind.ANNOTATION_CLASS
                else -> classKind
            }
        }

        val className = identifier.nameAsSafeName(if (modifiers.isCompanion()) "Companion" else "")
        val isLocal = isClassLocal(classNode) { getParent() }
        val classIsExpect = modifiers.hasExpect() || context.containerIsExpect

        return withChildClassName(className, isExpect = classIsExpect, isLocal) {
            val status = FirDeclarationStatusImpl(
                if (isLocal) Visibilities.Local else modifiers.getVisibility(),
                modifiers.getModality(isClassOrObject = true)
            ).apply {
                isExpect = classIsExpect
                isActual = modifiers.hasActual()
                isInner = modifiers.isInner()
                isCompanion = modifiers.isCompanion() && classKind == ClassKind.OBJECT
                isData = modifiers.isDataClass()
                isInline = modifiers.isInlineClass()
                isFun = modifiers.isFunctionalInterface()
                isExternal = modifiers.hasExternal()
            }

            val classSymbol = FirRegularClassSymbol(context.currentClassId)

            typeParameterList?.let { firTypeParameters += convertTypeParameters(it, typeConstraints, classSymbol) }

            withCapturedTypeParameters(status.isInner, firTypeParameters) {
                buildRegularClass {
                    source = classNode.toFirSourceElement()
                    moduleData = baseModuleData
                    origin = FirDeclarationOrigin.Source
                    name = className
                    this.status = status
                    this.classKind = classKind
                    scopeProvider = baseScopeProvider
                    symbol = classSymbol
                    annotations += modifiers.annotations
                    typeParameters += firTypeParameters

                    context.applyToActualCapturedTypeParameters(true) {
                        typeParameters += buildOuterClassTypeParameterRef { symbol = it }
                    }

                    val selfType = classNode.toDelegatedSelfType(this)
                    registerSelfType(selfType)

                    val delegationSpecifiers = superTypeList?.let { convertDelegationSpecifiers(it) }
                    var delegatedSuperTypeRef: FirTypeRef? = delegationSpecifiers?.delegatedSuperTypeRef
                    val delegatedConstructorSource: FirLightSourceElement? = delegationSpecifiers?.delegatedConstructorSource

                    val superTypeCallEntry = delegationSpecifiers?.delegatedConstructorArguments.orEmpty()
                    val superTypeRefs = mutableListOf<FirTypeRef>()

                    delegationSpecifiers?.let { superTypeRefs += it.superTypesRef }

                    when {
                        modifiers.isEnum() && (classKind == ClassKind.ENUM_CLASS) -> {
                            delegatedSuperTypeRef = buildResolvedTypeRef {
                                type = ConeClassLikeTypeImpl(
                                    implicitEnumType.type.lookupTag,
                                    arrayOf(selfType.type),
                                    isNullable = false
                                )
                            }
                            superTypeRefs += delegatedSuperTypeRef
                        }
                        modifiers.isAnnotation() && (classKind == ClassKind.ANNOTATION_CLASS) -> {
                            superTypeRefs += implicitAnnotationType
                            delegatedSuperTypeRef = implicitAnyType
                        }
                    }

                    superTypeRefs.ifEmpty {
                        superTypeRefs += implicitAnyType
                        delegatedSuperTypeRef = implicitAnyType
                    }

                    this.superTypeRefs += superTypeRefs

                    val secondaryConstructors = classBody.getChildNodesByType(SECONDARY_CONSTRUCTOR)
                    val classWrapper = ClassWrapper(
                        className, modifiers, classKind, this,
                        hasPrimaryConstructor = primaryConstructor != null,
                        hasSecondaryConstructor = secondaryConstructors.isNotEmpty(),
                        hasDefaultConstructor = if (primaryConstructor != null) !primaryConstructor!!.hasValueParameters()
                        else secondaryConstructors.isEmpty() || secondaryConstructors.any { !it.hasValueParameters() },
                        delegatedSelfTypeRef = selfType,
                        delegatedSuperTypeRef = delegatedSuperTypeRef ?: buildImplicitTypeRef(),
                        superTypeCallEntry = superTypeCallEntry
                    )
                    //parse primary constructor
                    val primaryConstructorWrapper = convertPrimaryConstructor(
                        primaryConstructor,
                        selfType.source,
                        classWrapper,
                        delegatedConstructorSource,
                        containingClassIsExpectClass = status.isExpect
                    )
                    val firPrimaryConstructor = primaryConstructorWrapper?.firConstructor
                    firPrimaryConstructor?.let { declarations += it }
                    delegationSpecifiers?.delegateFields?.map { declarations += it }

                    val properties = mutableListOf<FirProperty>()
                    if (primaryConstructor != null && firPrimaryConstructor != null) {
                        //parse properties
                        properties += primaryConstructorWrapper.valueParameters
                            .filter { it.hasValOrVar() }
                            .map {
                                it.toFirProperty(
                                    baseModuleData,
                                    callableIdForName(it.firValueParameter.name),
                                    classWrapper.hasExpect(),
                                    currentDispatchReceiverType()
                                )
                            }
                        addDeclarations(properties)
                    }

                    //parse declarations
                    classBody?.let {
                        addDeclarations(convertClassBody(it, classWrapper))
                    }

                    //parse data class
                    if (modifiers.isDataClass() && firPrimaryConstructor != null) {
                        val zippedParameters = properties.map { it.source!!.lighterASTNode to it }
                        DataClassMembersGenerator(
                            classNode,
                            this,
                            zippedParameters,
                            context.packageFqName,
                            context.className,
                            createClassTypeRefWithSourceKind = { firPrimaryConstructor.returnTypeRef },
                            createParameterTypeRefWithSourceKind = { property, _ -> property.returnTypeRef },
                        ).generate()
                    }

                    if (modifiers.isEnum()) {
                        generateValuesFunction(
                            baseModuleData,
                            context.packageFqName,
                            context.className,
                            modifiers.hasExpect()
                        )
                        generateValueOfFunction(
                            baseModuleData,
                            context.packageFqName,
                            context.className,
                            modifiers.hasExpect()
                        )
                    }
                }
            }
        }.also {
            it.initContainingClassForLocalAttr()
            fillDanglingConstraintsTo(firTypeParameters, typeConstraints, it)
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinExpressionParsing.parseObjectLiteral
     * @see org.jetbrains.kotlin.fir.builder.RawFirBuilder.Visitor.visitObjectLiteralExpression
     */
    fun convertObjectLiteral(objectLiteral: LighterASTNode): FirElement {
        return withChildClassName(SpecialNames.ANONYMOUS, isExpect = false) {
            buildAnonymousObjectExpression {
                val objectDeclaration = objectLiteral.getChildNodesByType(OBJECT_DECLARATION).first()
                val sourceElement = objectDeclaration.toFirSourceElement()
                source = sourceElement
                anonymousObject = buildAnonymousObject {
                    source = objectDeclaration.toFirSourceElement()
                    origin = FirDeclarationOrigin.Source
                    moduleData = baseModuleData
                    classKind = ClassKind.OBJECT
                    scopeProvider = baseScopeProvider
                    symbol = FirAnonymousObjectSymbol()
                    context.applyToActualCapturedTypeParameters(false) {
                        typeParameters += buildOuterClassTypeParameterRef { this.symbol = it }
                    }
                    val delegatedSelfType = objectLiteral.toDelegatedSelfType(this)
                    registerSelfType(delegatedSelfType)

                    var modifiers = Modifier()
                    var primaryConstructor: LighterASTNode? = null
                    val superTypeRefs = mutableListOf<FirTypeRef>()
                    val superTypeCallEntry = mutableListOf<FirExpression>()
                    var delegatedSuperTypeRef: FirTypeRef? = null
                    var classBody: LighterASTNode? = null
                    var delegatedConstructorSource: FirLightSourceElement? = null
                    var delegateFields: List<FirField>? = null

                    objectDeclaration.forEachChildren {
                        when (it.tokenType) {
                            MODIFIER_LIST -> modifiers = convertModifierList(it)
                            PRIMARY_CONSTRUCTOR -> primaryConstructor = it
                            SUPER_TYPE_LIST -> convertDelegationSpecifiers(it).let { specifiers ->
                                delegatedSuperTypeRef = specifiers.delegatedSuperTypeRef
                                superTypeRefs += specifiers.superTypesRef
                                superTypeCallEntry += specifiers.delegatedConstructorArguments
                                delegatedConstructorSource = specifiers.delegatedConstructorSource
                                delegateFields = specifiers.delegateFields
                            }
                            CLASS_BODY -> classBody = it
                        }
                    }

                    superTypeRefs.ifEmpty {
                        superTypeRefs += implicitAnyType
                        delegatedSuperTypeRef = implicitAnyType
                    }
                    val delegatedSuperType = delegatedSuperTypeRef ?: buildImplicitTypeRef()

                    annotations += modifiers.annotations
                    this.superTypeRefs += superTypeRefs

                    val classWrapper = ClassWrapper(
                        SpecialNames.NO_NAME_PROVIDED, modifiers, ClassKind.OBJECT, this,
                        hasPrimaryConstructor = false,
                        hasSecondaryConstructor = classBody.getChildNodesByType(SECONDARY_CONSTRUCTOR).isNotEmpty(),
                        hasDefaultConstructor = false,
                        delegatedSelfTypeRef = delegatedSelfType,
                        delegatedSuperTypeRef = delegatedSuperType,
                        superTypeCallEntry = superTypeCallEntry
                    )
                    //parse primary constructor
                    convertPrimaryConstructor(
                        primaryConstructor,
                        delegatedSelfType.source,
                        classWrapper,
                        delegatedConstructorSource,
                        containingClassIsExpectClass = false
                    )?.let { this.declarations += it.firConstructor }
                    delegateFields?.let { this.declarations += it }

                    //parse declarations
                    classBody?.let {
                        this.declarations += convertClassBody(it, classWrapper)
                    }
                }
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseEnumEntry
     */
    private fun convertEnumEntry(enumEntry: LighterASTNode, classWrapper: ClassWrapper): FirEnumEntry {
        var modifiers = Modifier()
        lateinit var identifier: String
        val enumSuperTypeCallEntry = mutableListOf<FirExpression>()
        var classBodyNode: LighterASTNode? = null
        var superTypeCallEntry: LighterASTNode? = null
        enumEntry.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                IDENTIFIER -> identifier = it.asText
                INITIALIZER_LIST -> {
                    enumSuperTypeCallEntry += convertInitializerList(it)
                    it.getChildNodeByType(SUPER_TYPE_CALL_ENTRY)?.let { superTypeCall ->
                        superTypeCallEntry = superTypeCall
                    }
                }
                CLASS_BODY -> classBodyNode = it
            }
        }

        val enumEntryName = identifier.nameAsSafeName()
        return buildEnumEntry {
            source = enumEntry.toFirSourceElement()
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = classWrapper.delegatedSelfTypeRef
            name = enumEntryName
            symbol = FirEnumEntrySymbol(CallableId(context.currentClassId, enumEntryName))
            status = FirDeclarationStatusImpl(Visibilities.Public, Modality.FINAL).apply {
                isStatic = true
                isExpect = classWrapper.hasExpect() || context.containerIsExpect
            }
            if (classWrapper.hasDefaultConstructor && enumEntry.getChildNodeByType(INITIALIZER_LIST) == null &&
                modifiers.annotations.isEmpty() && classBodyNode == null
            ) {
                return@buildEnumEntry
            }
            annotations += modifiers.annotations
            initializer = withChildClassName(enumEntryName, isExpect = false) {
                buildAnonymousObjectExpression {
                    val entrySource = enumEntry.toFirSourceElement(FirFakeSourceElementKind.EnumInitializer)
                    source = entrySource
                    anonymousObject = buildAnonymousObject {
                        source = entrySource
                        moduleData = baseModuleData
                        origin = FirDeclarationOrigin.Source
                        classKind = ClassKind.ENUM_ENTRY
                        scopeProvider = baseScopeProvider
                        symbol = FirAnonymousObjectSymbol()
                        val enumClassWrapper = ClassWrapper(
                            enumEntryName, modifiers, ClassKind.ENUM_ENTRY, this,
                            hasPrimaryConstructor = true,
                            hasSecondaryConstructor = classBodyNode.getChildNodesByType(SECONDARY_CONSTRUCTOR).isNotEmpty(),
                            hasDefaultConstructor = false,
                            delegatedSelfTypeRef = buildResolvedTypeRef {
                                type = ConeClassLikeTypeImpl(
                                    this@buildAnonymousObject.symbol.toLookupTag(),
                                    emptyArray(),
                                    isNullable = false
                                )
                            }.also { registerSelfType(it) },
                            delegatedSuperTypeRef = classWrapper.delegatedSelfTypeRef,
                            superTypeCallEntry = enumSuperTypeCallEntry
                        )
                        superTypeRefs += enumClassWrapper.delegatedSuperTypeRef
                        convertPrimaryConstructor(
                            null,
                            enumEntry.toFirSourceElement(),
                            enumClassWrapper,
                            superTypeCallEntry?.toFirSourceElement(),
                            isEnumEntry = true,
                            containingClassIsExpectClass = false
                        )?.let { declarations += it.firConstructor }
                        classBodyNode?.also {
                            // Use ANONYMOUS_OBJECT_NAME for the owner class id of enum entry declarations
                            withChildClassName(SpecialNames.ANONYMOUS, forceLocalContext = true, isExpect = false) {
                                declarations += convertClassBody(it, enumClassWrapper)
                            }
                        }
                    }
                }
            }
        }.also {
            it.containingClassForStaticMemberAttr = currentDispatchReceiverType()!!.lookupTag
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseEnumEntry
     */
    private fun convertInitializerList(initializerList: LighterASTNode): List<FirExpression> {
        val firValueArguments = mutableListOf<FirExpression>()
        initializerList.forEachChildren {
            when (it.tokenType) {
                SUPER_TYPE_CALL_ENTRY -> convertConstructorInvocation(it).apply {
                    firValueArguments += second
                }
            }
        }

        return firValueArguments
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseClassBody
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseEnumClassBody
     */
    private fun convertClassBody(classBody: LighterASTNode, classWrapper: ClassWrapper): List<FirDeclaration> {
        return classBody.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                ENUM_ENTRY -> container += convertEnumEntry(node, classWrapper)
                CLASS -> container += convertClass(node)
                FUN -> container += convertFunctionDeclaration(node) as FirDeclaration
                PROPERTY -> container += convertPropertyDeclaration(node, classWrapper)
                TYPEALIAS -> container += convertTypeAlias(node)
                OBJECT_DECLARATION -> container += convertClass(node)
                CLASS_INITIALIZER -> container += convertAnonymousInitializer(node) //anonymousInitializer
                SECONDARY_CONSTRUCTOR -> container += convertSecondaryConstructor(node, classWrapper)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseClassOrObject
     * primaryConstructor branch
     */
    private fun convertPrimaryConstructor(
        primaryConstructor: LighterASTNode?,
        selfTypeSource: FirSourceElement?,
        classWrapper: ClassWrapper,
        delegatedConstructorSource: FirLightSourceElement?,
        isEnumEntry: Boolean = false,
        containingClassIsExpectClass: Boolean
    ): PrimaryConstructor? {
        if (primaryConstructor == null && !classWrapper.isEnumEntry() && classWrapper.hasSecondaryConstructor) return null
        val classKind = classWrapper.classBuilder.classKind
        if (primaryConstructor == null && (containingClassIsExpectClass && classKind != ClassKind.ENUM_CLASS)) return null
        if (classWrapper.isInterface()) return null

        var modifiers = Modifier()
        val valueParameters = mutableListOf<ValueParameter>()
        primaryConstructor?.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                VALUE_PARAMETER_LIST -> valueParameters += convertValueParameters(it)
            }
        }

        val defaultVisibility = classWrapper.defaultConstructorVisibility()
        val firDelegatedCall = buildDelegatedConstructorCall {
            source = delegatedConstructorSource ?: selfTypeSource?.fakeElement(FirFakeSourceElementKind.DelegatingConstructorCall)
            constructedTypeRef = classWrapper.delegatedSuperTypeRef.copyWithNewSourceKind(FirFakeSourceElementKind.ImplicitTypeRef)
            isThis = false
            calleeReference = buildExplicitSuperReference {
                //[dirty] in case of enum classWrapper.delegatedSuperTypeRef.source is whole enum source
                source = if (!isEnumEntry) {
                    classWrapper.delegatedSuperTypeRef.source?.fakeElement(FirFakeSourceElementKind.DelegatingConstructorCall)
                        ?: this@buildDelegatedConstructorCall.source?.fakeElement(FirFakeSourceElementKind.DelegatingConstructorCall)
                } else {
                    delegatedConstructorSource
                        ?.lighterASTNode
                        ?.getChildNodeByType(CONSTRUCTOR_CALLEE)
                        ?.toFirSourceElement(FirFakeSourceElementKind.DelegatingConstructorCall)
                        ?: this@buildDelegatedConstructorCall.source
                }

                superTypeRef = this@buildDelegatedConstructorCall.constructedTypeRef
            }
            extractArgumentsFrom(classWrapper.superTypeCallEntry)
        }

        val explicitVisibility = runIf(primaryConstructor != null) {
            modifiers.getVisibility().takeUnless { it == Visibilities.Unknown }
        }
        val status = FirDeclarationStatusImpl(explicitVisibility ?: defaultVisibility, Modality.FINAL).apply {
            isExpect = modifiers.hasExpect() || context.containerIsExpect
            isActual = modifiers.hasActual()
            isInner = classWrapper.isInner()
            isFromSealedClass = classWrapper.isSealed() && explicitVisibility !== Visibilities.Private
            isFromEnumClass = classWrapper.isEnum()
        }

        return PrimaryConstructor(
            buildPrimaryConstructor {
                source = primaryConstructor?.toFirSourceElement()
                    ?: selfTypeSource?.fakeElement(FirFakeSourceElementKind.ImplicitConstructor)
                moduleData = baseModuleData
                origin = FirDeclarationOrigin.Source
                returnTypeRef = classWrapper.delegatedSelfTypeRef
                this.status = status
                symbol = FirConstructorSymbol(callableIdForClassConstructor())
                annotations += modifiers.annotations
                typeParameters += constructorTypeParametersFromConstructedClass(classWrapper.classBuilder.typeParameters)
                this.valueParameters += valueParameters.map { it.firValueParameter }
                delegatedConstructor = firDelegatedCall
                this.body = null
            }.apply {
                containingClassForStaticMemberAttr = currentDispatchReceiverType()!!.lookupTag
            }, valueParameters
        )
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseMemberDeclarationRest
     * at INIT keyword
     */
    private fun convertAnonymousInitializer(anonymousInitializer: LighterASTNode): FirDeclaration {
        var firBlock: FirBlock? = null
        anonymousInitializer.forEachChildren {
            when (it.tokenType) {
                BLOCK -> firBlock = convertBlock(it)
            }
        }

        return buildAnonymousInitializer {
            source = anonymousInitializer.toFirSourceElement()
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            body = firBlock ?: buildEmptyExpressionBlock()
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseSecondaryConstructor
     */
    private fun convertSecondaryConstructor(secondaryConstructor: LighterASTNode, classWrapper: ClassWrapper): FirConstructor {
        var modifiers = Modifier()
        val firValueParameters = mutableListOf<ValueParameter>()
        var constructorDelegationCall: FirDelegatedConstructorCall? = null
        var block: LighterASTNode? = null

        secondaryConstructor.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                VALUE_PARAMETER_LIST -> firValueParameters += convertValueParameters(it)
                CONSTRUCTOR_DELEGATION_CALL -> constructorDelegationCall = convertConstructorDelegationCall(it, classWrapper)
                BLOCK -> block = it
            }
        }

        val delegatedSelfTypeRef = classWrapper.delegatedSelfTypeRef

        val explicitVisibility = modifiers.getVisibility()
        val status = FirDeclarationStatusImpl(explicitVisibility, Modality.FINAL).apply {
            isExpect = modifiers.hasExpect() || context.containerIsExpect
            isActual = modifiers.hasActual()
            isInner = classWrapper.isInner()
            isFromSealedClass = classWrapper.isSealed() && explicitVisibility !== Visibilities.Private
            isFromEnumClass = classWrapper.isEnum()
        }

        val target = FirFunctionTarget(labelName = null, isLambda = false)
        return buildConstructor {
            source = secondaryConstructor.toFirSourceElement()
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = delegatedSelfTypeRef
            this.status = status
            symbol = FirConstructorSymbol(callableIdForClassConstructor())
            delegatedConstructor = constructorDelegationCall

            context.firFunctionTargets += target
            annotations += modifiers.annotations
            typeParameters += constructorTypeParametersFromConstructedClass(classWrapper.classBuilder.typeParameters)
            valueParameters += firValueParameters.map { it.firValueParameter }
            val (body, _) = convertFunctionBody(block, null)
            this.body = body
            context.firFunctionTargets.removeLast()
        }.also {
            it.containingClassForStaticMemberAttr = currentDispatchReceiverType()!!.lookupTag
            target.bind(it)
        }
    }

    /**
     * @see org.jetbrains.kotlin.fir.builder.RawFirBuilder.Visitor.convert(
     * KtConstructorDelegationCall, FirTypeRef, Boolean)
     */
    private fun convertConstructorDelegationCall(
        constructorDelegationCall: LighterASTNode,
        classWrapper: ClassWrapper
    ): FirDelegatedConstructorCall {
        var thisKeywordPresent = false
        val firValueArguments = mutableListOf<FirExpression>()
        constructorDelegationCall.forEachChildren {
            when (it.tokenType) {
                CONSTRUCTOR_DELEGATION_REFERENCE -> if (it.asText == "this") thisKeywordPresent = true
                VALUE_ARGUMENT_LIST -> firValueArguments += expressionConverter.convertValueArguments(it)
            }
        }

        val isImplicit = constructorDelegationCall.asText.isEmpty()
        val isThis = thisKeywordPresent //|| (isImplicit && classWrapper.hasPrimaryConstructor)
        val delegatedType =
            when {
                isThis -> classWrapper.delegatedSelfTypeRef
                else -> classWrapper.delegatedSuperTypeRef
            }

        return buildDelegatedConstructorCall {
            source = if (isImplicit) {
                constructorDelegationCall.toFirSourceElement().fakeElement(FirFakeSourceElementKind.ImplicitConstructor)
            } else {
                constructorDelegationCall.toFirSourceElement()
            }
            constructedTypeRef = delegatedType.copyWithNewSourceKind(FirFakeSourceElementKind.ImplicitTypeRef)
            this.isThis = isThis
            val calleeKind =
                if (isImplicit) FirFakeSourceElementKind.ImplicitConstructor else FirFakeSourceElementKind.DelegatingConstructorCall
            val calleeSource = constructorDelegationCall.getChildNodeByType(CONSTRUCTOR_DELEGATION_REFERENCE)
                ?.toFirSourceElement(calleeKind)
                ?: this@buildDelegatedConstructorCall.source?.fakeElement(calleeKind)
            calleeReference = if (isThis) {
                buildExplicitThisReference {
                    this.source = calleeSource
                }
            } else {
                buildExplicitSuperReference {
                    source = calleeSource
                    superTypeRef = this@buildDelegatedConstructorCall.constructedTypeRef
                }
            }
            extractArgumentsFrom(firValueArguments)
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeAlias
     */
    private fun convertTypeAlias(typeAlias: LighterASTNode): FirDeclaration {
        var modifiers = Modifier()
        var identifier: String? = null
        lateinit var firType: FirTypeRef

        typeAlias.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                IDENTIFIER -> identifier = it.asText
                TYPE_REFERENCE -> firType = convertType(it)
            }
        }

        val typeAliasName = identifier.nameAsSafeName()
        val typeAliasIsExpect = modifiers.hasExpect() || context.containerIsExpect
        return withChildClassName(typeAliasName, isExpect = typeAliasIsExpect) {
            val classSymbol = FirTypeAliasSymbol(context.currentClassId)

            val firTypeParameters = mutableListOf<FirTypeParameter>()
            typeAlias.forEachChildren {
                if (it.tokenType == TYPE_PARAMETER_LIST) {
                    firTypeParameters += convertTypeParameters(it, emptyList(), classSymbol)
                }
            }
            return@withChildClassName buildTypeAlias {
                source = typeAlias.toFirSourceElement()
                moduleData = baseModuleData
                origin = FirDeclarationOrigin.Source
                name = typeAliasName
                status = FirDeclarationStatusImpl(modifiers.getVisibility(), Modality.FINAL).apply {
                    isExpect = typeAliasIsExpect
                    isActual = modifiers.hasActual()
                }
                symbol = classSymbol
                expandedTypeRef = firType
                annotations += modifiers.annotations
                typeParameters += firTypeParameters
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseProperty
     */
    fun convertPropertyDeclaration(property: LighterASTNode, classWrapper: ClassWrapper? = null): FirDeclaration {
        var modifiers = Modifier()
        var identifier: String? = null
        val firTypeParameters = mutableListOf<FirTypeParameter>()
        var isReturnType = false
        var delegateExpression: LighterASTNode? = null
        var isVar = false
        var receiverType: FirTypeRef? = null
        var returnType: FirTypeRef = implicitType
        val typeConstraints = mutableListOf<TypeConstraint>()
        val accessors = mutableListOf<LighterASTNode>()
        var propertyInitializer: FirExpression? = null
        var typeParameterList: LighterASTNode? = null
        var fieldDeclaration: LighterASTNode? = null
        property.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                IDENTIFIER -> identifier = it.asText
                TYPE_PARAMETER_LIST -> typeParameterList = it
                COLON -> isReturnType = true
                TYPE_REFERENCE -> if (isReturnType) returnType = convertType(it) else receiverType = convertType(it)
                TYPE_CONSTRAINT_LIST -> typeConstraints += convertTypeConstraints(it)
                PROPERTY_DELEGATE -> delegateExpression = it
                VAR_KEYWORD -> isVar = true
                PROPERTY_ACCESSOR -> {
                    accessors += it
                }
                BACKING_FIELD -> fieldDeclaration = it
                else -> if (it.isExpression()) propertyInitializer = expressionConverter.getAsFirExpression(it, "Should have initializer")
            }
        }

        val propertyName = identifier.nameAsSafeName()

        val parentNode = property.getParent()
        val isLocal = !(parentNode?.tokenType == KT_FILE || parentNode?.tokenType == CLASS_BODY)
        val propertySource = property.toFirSourceElement()

        return buildProperty {
            source = propertySource
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = returnType
            name = propertyName
            this.isVar = isVar
            initializer = propertyInitializer

            //probably can do this for delegateExpression itself
            val delegateSource = delegateExpression?.let {
                (it.getExpressionInParentheses() ?: it).toFirSourceElement()
            }

            symbol = if (isLocal) FirPropertySymbol(propertyName) else FirPropertySymbol(callableIdForName(propertyName))

            typeParameterList?.let { firTypeParameters += convertTypeParameters(it, typeConstraints, symbol) }

            backingField = fieldDeclaration.convertBackingField(symbol, modifiers, returnType, isVar)

            if (isLocal) {
                this.isLocal = true
                val delegateBuilder = delegateExpression?.let {
                    FirWrappedDelegateExpressionBuilder().apply {
                        source = delegateSource
                        expression = expressionConverter.getAsFirExpression(it, "Incorrect delegate expression")
                    }
                }
                status = FirDeclarationStatusImpl(Visibilities.Local, Modality.FINAL).apply {
                    isLateInit = modifiers.hasLateinit()
                }

                val receiver = delegateExpression?.let {
                    expressionConverter.getAsFirExpression<FirExpression>(it, "Incorrect delegate expression")
                }

                generateAccessorsByDelegate(
                    delegateBuilder,
                    baseModuleData,
                    classWrapper?.classBuilder?.ownerRegularOrAnonymousObjectSymbol,
                    classWrapper?.classBuilder?.ownerRegularClassTypeParametersCount,
                    isExtension = false,
                    receiver = receiver
                )
            } else {
                this.isLocal = false
                receiverTypeRef = receiverType
                dispatchReceiverType = currentDispatchReceiverType()
                withCapturedTypeParameters(true, firTypeParameters) {
                    typeParameters += firTypeParameters

                    val delegateBuilder = delegateExpression?.let {
                        FirWrappedDelegateExpressionBuilder().apply {
                            source = delegateSource
                            expression = expressionConverter.getAsFirExpression(it, "Should have delegate")
                        }
                    }

                    val propertyVisibility = modifiers.getVisibility()

                    fun defaultAccessorStatus() =
                        // Downward propagation of `inline` and `external` modifiers (from property to its accessors)
                        FirDeclarationStatusImpl(propertyVisibility, modifiers.getModality(isClassOrObject = false)).apply {
                            isInline = modifiers.hasInline()
                            isExternal = modifiers.hasExternal()
                        }

                    val convertedAccessors = accessors.map {
                        convertGetterOrSetter(it, returnType, propertyVisibility, symbol, modifiers)
                    }
                    this.getter = convertedAccessors.find { it.isGetter }
                        ?: FirDefaultPropertyGetter(
                            property.toFirSourceElement(FirFakeSourceElementKind.DefaultAccessor), moduleData, FirDeclarationOrigin.Source, returnType, propertyVisibility, symbol,
                        ).also {
                            it.status = defaultAccessorStatus()
                            it.initContainingClassAttr()
                        }
                    // NOTE: We still need the setter even for a val property so we can report errors (e.g., VAL_WITH_SETTER).
                    this.setter = convertedAccessors.find { it.isSetter }
                        ?: if (isVar) {
                            FirDefaultPropertySetter(
                                property.toFirSourceElement(FirFakeSourceElementKind.DefaultAccessor),
                                moduleData,
                                FirDeclarationOrigin.Source,
                                returnType,
                                propertyVisibility, symbol,
                            ).also {
                                it.status = defaultAccessorStatus()
                                it.initContainingClassAttr()
                            }
                        } else null

                    status = FirDeclarationStatusImpl(propertyVisibility, modifiers.getModality(isClassOrObject = false)).apply {
                        isExpect = modifiers.hasExpect() || context.containerIsExpect
                        isActual = modifiers.hasActual()
                        isOverride = modifiers.hasOverride()
                        isConst = modifiers.isConst()
                        isLateInit = modifiers.hasLateinit()
                        isExternal = modifiers.hasExternal()
                    }

                    val receiver = delegateExpression?.let {
                        expressionConverter.getAsFirExpression<FirExpression>(it, "Should have delegate")
                    }
                    generateAccessorsByDelegate(
                        delegateBuilder,
                        baseModuleData,
                        classWrapper?.classBuilder?.ownerRegularOrAnonymousObjectSymbol,
                        classWrapper?.classBuilder?.ownerRegularClassTypeParametersCount,
                        isExtension = receiverType != null,
                        receiver = receiver
                    )
                }
            }
            annotations += modifiers.annotations
        }.also {
            fillDanglingConstraintsTo(firTypeParameters, typeConstraints, it)
        }
    }

    /**
     * @see org.jetbrains.kotlin.fir.builder.RawFirBuilder.Visitor.visitDestructuringDeclaration
     */
    private fun convertDestructingDeclaration(destructingDeclaration: LighterASTNode): DestructuringDeclaration {
        var isVar = false
        val entries = mutableListOf<FirVariable?>()
        val source = destructingDeclaration.toFirSourceElement()
        var firExpression: FirExpression? = null
        destructingDeclaration.forEachChildren {
            when (it.tokenType) {
                VAR_KEYWORD -> isVar = true
                DESTRUCTURING_DECLARATION_ENTRY -> entries += convertDestructingDeclarationEntry(it)
                else -> if (it.isExpression()) firExpression =
                    expressionConverter.getAsFirExpression(it, "Initializer required for destructuring declaration")
            }
        }

        return DestructuringDeclaration(
            isVar,
            entries,
            firExpression ?: buildErrorExpression(
                null,
                ConeSimpleDiagnostic("Initializer required for destructuring declaration", DiagnosticKind.Syntax)
            ),
            source
        )
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseMultiDeclarationName
     */
    private fun convertDestructingDeclarationEntry(entry: LighterASTNode): FirVariable? {
        var modifiers = Modifier()
        var identifier: String? = null
        var firType: FirTypeRef? = null
        entry.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                IDENTIFIER -> identifier = it.asText
                TYPE_REFERENCE -> firType = convertType(it)
            }
        }

        if (identifier == "_") return null
        val name = identifier.nameAsSafeName()
        return buildProperty {
            source = entry.toFirSourceElement()
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = firType ?: implicitType
            this.name = name
            isVar = false
            symbol = FirPropertySymbol(name)
            isLocal = true
            status = FirDeclarationStatusImpl(Visibilities.Local, Modality.FINAL)
            annotations += modifiers.annotations
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parsePropertyComponent
     */
    private fun convertGetterOrSetter(
        getterOrSetter: LighterASTNode,
        propertyTypeRef: FirTypeRef,
        propertyVisibility: Visibility,
        propertySymbol: FirPropertySymbol,
        propertyModifiers: Modifier,
    ): FirPropertyAccessor {
        var modifiers = Modifier()
        var isGetter = true
        var returnType: FirTypeRef? = null
        val propertyTypeRefToUse = propertyTypeRef.copyWithNewSourceKind(FirFakeSourceElementKind.ImplicitTypeRef)
        var firValueParameters: FirValueParameter = buildDefaultSetterValueParameter {
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = propertyTypeRefToUse
            symbol = FirValueParameterSymbol(DEFAULT_VALUE_PARAMETER)
        }
        var block: LighterASTNode? = null
        var expression: LighterASTNode? = null
        var outerContractDescription: FirContractDescription? = null
        getterOrSetter.forEachChildren {
            if (it.asText == "set") isGetter = false
            when (it.tokenType) {
                SET_KEYWORD -> isGetter = false
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                TYPE_REFERENCE -> returnType = convertType(it)
                VALUE_PARAMETER_LIST -> firValueParameters = convertSetterParameter(it, propertyTypeRefToUse)
                CONTRACT_EFFECT_LIST -> outerContractDescription = obtainContractDescription(it)
                BLOCK -> block = it
                else -> if (it.isExpression()) expression = it
            }
        }

        var accessorVisibility = modifiers.getVisibility()
        if (accessorVisibility == Visibilities.Unknown) {
            accessorVisibility = propertyVisibility
        }
        val status =
            // Downward propagation of `inline` and `external` modifiers (from property to its accessors)
            FirDeclarationStatusImpl(accessorVisibility, modifiers.getModality(isClassOrObject = false)).apply {
                isInline = propertyModifiers.hasInline() || modifiers.hasInline()
                isExternal = propertyModifiers.hasExternal() || modifiers.hasExternal()
            }
        val sourceElement = getterOrSetter.toFirSourceElement()
        if (block == null && expression == null) {
            return FirDefaultPropertyAccessor
                .createGetterOrSetter(
                    sourceElement,
                    baseModuleData,
                    FirDeclarationOrigin.Source,
                    propertyTypeRefToUse,
                    accessorVisibility,
                    propertySymbol,
                    isGetter
                )
                .also { accessor ->
                    accessor.annotations += modifiers.annotations
                    accessor.status = status
                    accessor.initContainingClassAttr()
                }
        }
        val target = FirFunctionTarget(labelName = null, isLambda = false)
        return buildPropertyAccessor {
            source = sourceElement
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = returnType ?: if (isGetter) propertyTypeRefToUse else implicitUnitType
            symbol = FirPropertyAccessorSymbol()
            this.isGetter = isGetter
            this.status = status
            context.firFunctionTargets += target
            annotations += modifiers.annotations

            if (!isGetter) {
                valueParameters += firValueParameters
            }

            val hasContractEffectList = outerContractDescription != null
            val bodyWithContractDescription = convertFunctionBody(block, expression, hasContractEffectList)
            this.body = bodyWithContractDescription.first
            val contractDescription = outerContractDescription ?: bodyWithContractDescription.second
            contractDescription?.let {
                this.contractDescription = it
            }
            context.firFunctionTargets.removeLast()
            this.propertySymbol = propertySymbol
        }.also {
            target.bind(it)
            it.initContainingClassAttr()
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parsePropertyComponent
     */
    private fun LighterASTNode?.convertBackingField(
        propertySymbol: FirPropertySymbol,
        propertyModifiers: Modifier,
        propertyReturnType: FirTypeRef,
        isVar: Boolean,
    ): FirBackingField {
        var modifiers = Modifier()
        var returnType: FirTypeRef = implicitType
        var backingFieldInitializer: FirExpression? = null
        this?.forEachChildren {
            when {
                it.tokenType == MODIFIER_LIST -> modifiers = convertModifierList(it)
                it.tokenType == TYPE_REFERENCE -> returnType = convertType(it)
                it.isExpression() -> {
                    backingFieldInitializer = expressionConverter.getAsFirExpression(it, "Should have initializer")
                }
            }
        }
        var componentVisibility = modifiers.getVisibility()
        if (componentVisibility == Visibilities.Unknown) {
            componentVisibility = Visibilities.Private
        }
        val status = obtainPropertyComponentStatus(componentVisibility, modifiers, propertyModifiers)
        val sourceElement = this?.toFirSourceElement()
        return if (this != null) {
            buildBackingField {
                source = sourceElement
                moduleData = baseModuleData
                origin = FirDeclarationOrigin.Source
                returnTypeRef = returnType
                name = StandardNames.BACKING_FIELD
                symbol = FirBackingFieldSymbol(CallableId(name))
                this.status = status
                annotations += modifiers.annotations
                this.propertySymbol = propertySymbol
                this.initializer = backingFieldInitializer
                this.isVar = isVar
                this.isVal = !isVar
            }
        } else {
            FirDefaultPropertyBackingField(
                moduleData = baseModuleData,
                annotations = modifiers.annotations,
                returnTypeRef = propertyReturnType.copyWithNewSourceKind(FirFakeSourceElementKind.DefaultAccessor),
                isVar = isVar,
                propertySymbol = propertySymbol,
                status = status,
            )
        }
    }

    private fun obtainPropertyComponentStatus(
        componentVisibility: Visibility,
        modifiers: Modifier,
        propertyModifiers: Modifier,
    ): FirDeclarationStatus {
        // Downward propagation of `inline` and `external` modifiers (from property to its accessors)
        return FirDeclarationStatusImpl(componentVisibility, modifiers.getModality(isClassOrObject = false)).apply {
            isInline = propertyModifiers.hasInline() || modifiers.hasInline()
            isExternal = propertyModifiers.hasExternal() || modifiers.hasExternal()
            isLateInit = modifiers.hasLateinit()
        }
    }

    private fun obtainContractDescription(rawContractDescription: LighterASTNode): FirContractDescription? =
        buildRawContractDescription {
            source = rawContractDescription.toFirSourceElement()
            extractRawEffects(rawContractDescription, rawEffects)
        }

    private fun extractRawEffects(rawContractDescription: LighterASTNode, destination: MutableList<FirExpression>) {
        rawContractDescription.forEachChildren {
            val errorReason = "The contract effect is not an expression"
            when (it.tokenType) {
                CONTRACT_EFFECT -> {
                    val effect = it.getFirstChild()
                    if (effect == null) {
                        val errorExpression =
                            buildErrorExpression(null, ConeSimpleDiagnostic(errorReason, DiagnosticKind.ExpressionExpected))
                        destination.add(errorExpression)
                    } else {
                        val expression = expressionConverter.convertExpression(effect, errorReason)
                        destination.add(expression as FirExpression)
                    }
                }
                else -> Unit
            }
        }
    }

    /**
     * this is just a VALUE_PARAMETER_LIST
     *
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parsePropertyGetterOrSetter
     * @see org.jetbrains.kotlin.fir.builder.RawFirBuilder.Visitor.toFirValueParameter
     */
    private fun convertSetterParameter(setterParameter: LighterASTNode, propertyTypeRef: FirTypeRef): FirValueParameter {
        var modifiers = Modifier()
        lateinit var firValueParameter: FirValueParameter
        setterParameter.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                VALUE_PARAMETER -> firValueParameter = convertValueParameter(it).firValueParameter
            }
        }

        return buildValueParameter {
            source = firValueParameter.source
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = if (firValueParameter.returnTypeRef == implicitType) propertyTypeRef else firValueParameter.returnTypeRef
            name = firValueParameter.name
            symbol = FirValueParameterSymbol(firValueParameter.name)
            defaultValue = firValueParameter.defaultValue
            isCrossinline = modifiers.hasCrossinline() || firValueParameter.isCrossinline
            isNoinline = modifiers.hasNoinline() || firValueParameter.isNoinline
            isVararg = modifiers.hasVararg() || firValueParameter.isVararg
            annotations += modifiers.annotations + firValueParameter.annotations
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseFunction
     */
    fun convertFunctionDeclaration(functionDeclaration: LighterASTNode): FirStatement {
        var modifiers = Modifier()
        var identifier: String? = null
        var valueParametersList: LighterASTNode? = null
        var isReturnType = false
        var receiverType: FirTypeRef? = null
        var returnType: FirTypeRef? = null
        val typeConstraints = mutableListOf<TypeConstraint>()
        var block: LighterASTNode? = null
        var expression: LighterASTNode? = null
        var hasEqToken = false
        var typeParameterList: LighterASTNode? = null
        var outerContractDescription: FirContractDescription? = null
        functionDeclaration.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                IDENTIFIER -> identifier = it.asText
                TYPE_PARAMETER_LIST -> typeParameterList = it
                VALUE_PARAMETER_LIST -> valueParametersList = it //must convert later, because it can contains "return"
                COLON -> isReturnType = true
                TYPE_REFERENCE -> if (isReturnType) returnType = convertType(it) else receiverType = convertType(it)
                TYPE_CONSTRAINT_LIST -> typeConstraints += convertTypeConstraints(it)
                CONTRACT_EFFECT_LIST -> outerContractDescription = obtainContractDescription(it)
                BLOCK -> block = it
                EQ -> hasEqToken = true
                else -> if (it.isExpression()) expression = it
            }
        }

        if (returnType == null) {
            returnType =
                if (block != null || !hasEqToken) implicitUnitType
                else implicitType
        }

        val parentNode = functionDeclaration.getParent()
        val isLocal = !(parentNode?.tokenType == KT_FILE || parentNode?.tokenType == CLASS_BODY)
        val target: FirFunctionTarget
        val functionSource = functionDeclaration.toFirSourceElement()
        val functionSymbol: FirFunctionSymbol<*>
        val isAnonymousFunction = identifier == null && isLocal
        val functionBuilder = if (isAnonymousFunction) {
            val labelName = functionDeclaration.getLabelName() ?: context.calleeNamesForLambda.lastOrNull()?.identifier
            target = FirFunctionTarget(labelName = labelName, isLambda = false)
            functionSymbol = FirAnonymousFunctionSymbol()
            FirAnonymousFunctionBuilder().apply {
                source = functionSource
                receiverTypeRef = receiverType
                symbol = functionSymbol
                isLambda = false
            }
        } else {
            val functionName = identifier.nameAsSafeName()
            val labelName = runIf(!functionName.isSpecial) { functionName.identifier }
            target = FirFunctionTarget(labelName, isLambda = false)
            functionSymbol = FirNamedFunctionSymbol(callableIdForName(functionName))
            FirSimpleFunctionBuilder().apply {
                source = functionSource
                receiverTypeRef = receiverType
                name = functionName
                status = FirDeclarationStatusImpl(
                    if (isLocal) Visibilities.Local else modifiers.getVisibility(),
                    modifiers.getModality(isClassOrObject = false)
                ).apply {
                    isExpect = modifiers.hasExpect() || context.containerIsExpect
                    isActual = modifiers.hasActual()
                    isOverride = modifiers.hasOverride()
                    isOperator = modifiers.hasOperator()
                    isInfix = modifiers.hasInfix()
                    isInline = modifiers.hasInline()
                    isTailRec = modifiers.hasTailrec()
                    isExternal = modifiers.hasExternal()
                    isSuspend = modifiers.hasSuspend()
                }

                symbol = functionSymbol
                dispatchReceiverType = currentDispatchReceiverType()
            }
        }

        val firTypeParameters = mutableListOf<FirTypeParameter>()
        typeParameterList?.let { firTypeParameters += convertTypeParameters(it, typeConstraints, functionSymbol) }

        val function = functionBuilder.apply {
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = returnType!!

            context.firFunctionTargets += target
            annotations += modifiers.annotations

            val actualTypeParameters = if (this is FirSimpleFunctionBuilder) {
                typeParameters += firTypeParameters
                typeParameters
            } else {
                listOf()
            }

            withCapturedTypeParameters(true, actualTypeParameters) {
                valueParametersList?.let { list ->
                    valueParameters += convertValueParameters(
                        list,
                        if (isAnonymousFunction) ValueParameterDeclaration.LAMBDA else ValueParameterDeclaration.OTHER
                    ).map { it.firValueParameter }
                }

                val hasContractEffectList = outerContractDescription != null
                val bodyWithContractDescription = convertFunctionBody(block, expression, hasContractEffectList)
                this.body = bodyWithContractDescription.first
                val contractDescription = outerContractDescription ?: bodyWithContractDescription.second
                contractDescription?.let {
                    // TODO: add error reporting for contracts on lambdas
                    if (this is FirSimpleFunctionBuilder) {
                        this.contractDescription = it
                    }
                }
            }
            context.firFunctionTargets.removeLast()
        }.build().also {
            target.bind(it)
            if (it is FirSimpleFunction) {
                fillDanglingConstraintsTo(firTypeParameters, typeConstraints, it)
            }
        }
        return if (function is FirAnonymousFunction) {
            buildAnonymousFunctionExpression {
                source = functionSource
                anonymousFunction = function
            }
        } else {
            function
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseFunctionBody
     * @see org.jetbrains.kotlin.fir.builder.RawFirBuilder.Visitor.buildFirBody
     */
    private fun convertFunctionBody(
        blockNode: LighterASTNode?,
        expression: LighterASTNode?,
        hasContractEffectList: Boolean = false
    ): Pair<FirBlock?, FirContractDescription?> {
        return when {
            blockNode != null -> {
                val block = convertBlock(blockNode)
                if (hasContractEffectList) {
                    block to null
                } else {
                    block.extractContractDescriptionIfPossible()
                }
            }
            expression != null -> FirSingleExpressionBlock(
                expressionConverter.getAsFirExpression<FirExpression>(expression, "Function has no body (but should)").toReturn()
            ) to null
            else -> null to null
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseBlock
     */
    fun convertBlock(block: LighterASTNode?): FirBlock {
        if (block == null) return buildEmptyExpressionBlock()
        if (block.tokenType != BLOCK) {
            return FirSingleExpressionBlock(
                expressionConverter.getAsFirExpression(block)
            )
        }

        val blockTree = LightTree2Fir.buildLightTreeBlockExpression(block.asText)
        return DeclarationsConverter(
            baseSession, baseScopeProvider, blockTree, offset = offset + tree.getStartOffset(block), context
        ).convertBlockExpression(blockTree.root)
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseDelegationSpecifierList
     * @see org.jetbrains.kotlin.fir.builder.RawFirBuilder.Visitor.extractSuperTypeListEntriesTo
     *
     * SUPER_TYPE_ENTRY             - userType
     * SUPER_TYPE_CALL_ENTRY        - constructorInvocation
     * DELEGATED_SUPER_TYPE_ENTRY   - explicitDelegation
     */
    //TODO make wrapper for result?
    private data class DelegationSpecifiers(
        val delegatedSuperTypeRef: FirTypeRef?,
        val superTypesRef: List<FirTypeRef>,
        val delegatedConstructorArguments: List<FirExpression>,
        val delegatedConstructorSource: FirLightSourceElement?,
        val delegateFields: List<FirField>,
    )

    private fun convertDelegationSpecifiers(delegationSpecifiers: LighterASTNode): DelegationSpecifiers {
        val superTypeRefs = mutableListOf<FirTypeRef>()
        val superTypeCallEntry = mutableListOf<FirExpression>()
        var delegatedSuperTypeRef: FirTypeRef? = null
        var delegateConstructorSource: FirLightSourceElement? = null
        val delegateFields = mutableListOf<FirField>()
        delegationSpecifiers.forEachChildren {
            when (it.tokenType) {
                SUPER_TYPE_ENTRY -> {
                    superTypeRefs += convertType(it)
                }
                SUPER_TYPE_CALL_ENTRY -> convertConstructorInvocation(it).apply {
                    delegatedSuperTypeRef = first
                    superTypeRefs += first
                    superTypeCallEntry += second
                    delegateConstructorSource = it.toFirSourceElement(FirFakeSourceElementKind.DelegatingConstructorCall)
                }
                DELEGATED_SUPER_TYPE_ENTRY -> {
                    superTypeRefs += convertExplicitDelegation(it, delegateFields)
                }
            }
        }
        return DelegationSpecifiers(
            delegatedSuperTypeRef, superTypeRefs, superTypeCallEntry, delegateConstructorSource, delegateFields
        )
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseDelegationSpecifier
     *
     * constructorInvocation
     *   : userType valueArguments
     *   ;
     */
    private fun convertConstructorInvocation(constructorInvocation: LighterASTNode): Pair<FirTypeRef, List<FirExpression>> {
        var firTypeRef: FirTypeRef = implicitType
        val firValueArguments = mutableListOf<FirExpression>()
        constructorInvocation.forEachChildren {
            when (it.tokenType) {
                CONSTRUCTOR_CALLEE -> if (it.asText.isNotEmpty()) firTypeRef = convertType(it)   //is empty in enum entry constructor
                VALUE_ARGUMENT_LIST -> firValueArguments += expressionConverter.convertValueArguments(it)
            }
        }
        return Pair(firTypeRef, firValueArguments)
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseDelegationSpecifier
     *
     * explicitDelegation
     *   : userType "by" element
     *   ;
     */
    private fun convertExplicitDelegation(explicitDelegation: LighterASTNode, delegateFields: MutableList<FirField>): FirTypeRef {
        lateinit var firTypeRef: FirTypeRef
        var firExpression: FirExpression? = null
        explicitDelegation.forEachChildren {
            when (it.tokenType) {
                TYPE_REFERENCE -> firTypeRef = convertType(it)
                else -> if (it.isExpression()) firExpression = expressionConverter.getAsFirExpression(it, "Should have delegate")
            }
        }

        val calculatedFirExpression = firExpression ?: buildErrorExpression(
            explicitDelegation.toFirSourceElement(), ConeSimpleDiagnostic("Should have delegate", DiagnosticKind.Syntax)
        )

        val delegateName = Name.special("<\$\$delegate_${delegateFields.size}>")
        delegateFields.add(
            buildField {
                source = calculatedFirExpression.source?.fakeElement(FirFakeSourceElementKind.ClassDelegationField)
                moduleData = baseModuleData
                origin = FirDeclarationOrigin.Synthetic
                name = delegateName
                returnTypeRef = firTypeRef
                symbol = FirFieldSymbol(CallableId(name))
                isVar = false
                status = FirDeclarationStatusImpl(Visibilities.Local, Modality.FINAL)
                initializer = calculatedFirExpression
            }
        )
        return firTypeRef
    }

    /*****    TYPES    *****/
    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeParameterList
     */
    private fun convertTypeParameters(
        typeParameterList: LighterASTNode,
        typeConstraints: List<TypeConstraint>,
        containingDeclarationSymbol: FirBasedSymbol<*>
    ): List<FirTypeParameter> {
        return typeParameterList.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                TYPE_PARAMETER -> container += convertTypeParameter(node, typeConstraints, containingDeclarationSymbol)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeConstraintList
     */
    private fun convertTypeConstraints(typeConstraints: LighterASTNode): List<TypeConstraint> {
        return typeConstraints.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                TYPE_CONSTRAINT -> container += convertTypeConstraint(node)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeConstraint
     */
    private fun convertTypeConstraint(typeConstraint: LighterASTNode): TypeConstraint {
        lateinit var identifier: String
        lateinit var firType: FirTypeRef
        lateinit var referenceExpression: LighterASTNode
        val annotations = mutableListOf<FirAnnotation>()
        typeConstraint.forEachChildren {
            when (it.tokenType) {
                //annotations will be saved later, on mapping stage with type parameters
                ANNOTATION, ANNOTATION_ENTRY -> annotations += convertAnnotation(it)
                REFERENCE_EXPRESSION -> {
                    identifier = it.asText
                    referenceExpression = it
                }
                TYPE_REFERENCE -> firType = convertType(it)
            }
        }

        return TypeConstraint(annotations, identifier, firType, referenceExpression.toFirSourceElement())
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeParameter
     */
    private fun convertTypeParameter(
        typeParameter: LighterASTNode,
        typeConstraints: List<TypeConstraint>,
        containingSymbol: FirBasedSymbol<*>
    ): FirTypeParameter {
        var typeParameterModifiers = TypeParameterModifier()
        var identifier: String? = null
        var firType: FirTypeRef? = null
        typeParameter.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> typeParameterModifiers = convertTypeParameterModifiers(it)
                IDENTIFIER -> identifier = it.asText
                TYPE_REFERENCE -> firType = convertType(it)
            }
        }

        return buildTypeParameter {
            source = typeParameter.toFirSourceElement()
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            name = identifier.nameAsSafeName()
            symbol = FirTypeParameterSymbol()
            containingDeclarationSymbol = containingSymbol
            variance = typeParameterModifiers.getVariance()
            isReified = typeParameterModifiers.hasReified()
            annotations += typeParameterModifiers.annotations
            firType?.let { bounds += it }
            for (typeConstraint in typeConstraints) {
                if (typeConstraint.identifier == identifier) {
                    bounds += typeConstraint.firTypeRef
                    annotations += typeConstraint.annotations
                }
            }
            addDefaultBoundIfNecessary()
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeRef
     */
    fun convertType(type: LighterASTNode): FirTypeRef {
        val typeRefSource = type.toFirSourceElement()
        if (type.asText.isEmpty()) {
            return buildErrorTypeRef {
                source = typeRefSource
                diagnostic = ConeSimpleDiagnostic("Unwrapped type is null", DiagnosticKind.Syntax)
            }
        }

        // There can be MODIFIER_LIST children on the TYPE_REFERENCE node AND the descendant NULLABLE_TYPE nodes.
        // We aggregate them to get modifiers and annotations. Not only that, there could be multiple modifier lists on each. Examples:
        //
        //   `@A() (@B Int)`   -> Has 2 modifier lists (@A and @B) in TYPE_REFERENCE
        //   `(@A() (@B Int))? -> No modifier list on TYPE_REFERENCE, but 2 modifier lists (@A and @B) on child NULLABLE_TYPE
        //   `@A() (@B Int)?   -> Has 1 modifier list (@A) on TYPE_REFERENCE, and 1 modifier list (@B) on child NULLABLE_TYPE
        //   `@A (@B() (@C() (@Bar D)?)?)?` -> Has 1 modifier list (@A) on B and 1 modifier list on each of the
        //                                     3 descendant NULLABLE_TYPE (@B, @C, @D)
        //
        // We need to examine all modifier lists for some cases:
        // 1. `@A Int?` and `(@A Int)?` are effectively the same, but in the latter, the modifier list is on the child NULLABLE_TYPE
        // 2. `(suspend @A () -> Int)?` is a nullable suspend function type but the modifier list is on the child NULLABLE_TYPE
        //
        // TODO: Report MODIFIER_LIST_NOT_ALLOWED error when there are multiple modifier lists. How do we report on each of them?
        val allTypeModifiers = mutableListOf<TypeModifier>()

        var firType: FirTypeRef? = null
        type.forEachChildren {
            when (it.tokenType) {
                TYPE_REFERENCE -> firType = convertType(it)
                MODIFIER_LIST -> allTypeModifiers += convertTypeModifierList(it)
                USER_TYPE -> firType = convertUserType(typeRefSource, it)
                NULLABLE_TYPE -> firType = convertNullableType(typeRefSource, it, allTypeModifiers)
                FUNCTION_TYPE -> firType = convertFunctionType(typeRefSource, it, isSuspend = allTypeModifiers.hasSuspend())
                DYNAMIC_TYPE -> firType = buildDynamicTypeRef {
                    source = typeRefSource
                    isMarkedNullable = false
                }
                INTERSECTION_TYPE -> firType =
                    buildErrorTypeRef {
                        source = typeRefSource
                        diagnostic = ConeSimpleDiagnostic("Intersection types are not supported yet", DiagnosticKind.Syntax)
                    }
                TokenType.ERROR_ELEMENT -> firType =
                    buildErrorTypeRef {
                        source = typeRefSource
                        diagnostic = ConeSimpleDiagnostic("Unwrapped type is null", DiagnosticKind.Syntax)
                    }
            }
        }

        val calculatedFirType = firType ?: buildErrorTypeRef {
            source = typeRefSource
            diagnostic = ConeSimpleDiagnostic("Incomplete code", DiagnosticKind.Syntax)
        }

        for (modifierList in allTypeModifiers) {
            (calculatedFirType.annotations as MutableList<FirAnnotation>) += modifierList.annotations
        }
        return calculatedFirType
    }

    private fun Collection<TypeModifier>.hasSuspend() = any { it.hasSuspend() }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeRefContents
     */
    private fun convertReceiverType(receiverType: LighterASTNode): FirTypeRef {
        receiverType.forEachChildren {
            when (it.tokenType) {
                TYPE_REFERENCE -> return convertType(it)
            }
        }

        //TODO specify error
        throw Exception()
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseNullableTypeSuffix
     */
    private fun convertNullableType(
        typeRefSource: FirSourceElement,
        nullableType: LighterASTNode,
        allTypeModifiers: MutableList<TypeModifier>,
        isNullable: Boolean = true
    ): FirTypeRef {
        lateinit var firType: FirTypeRef
        nullableType.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> allTypeModifiers += convertTypeModifierList(it)
                USER_TYPE -> firType = convertUserType(typeRefSource, it, isNullable)
                FUNCTION_TYPE -> firType = convertFunctionType(typeRefSource, it, isNullable, isSuspend = allTypeModifiers.hasSuspend())
                NULLABLE_TYPE -> firType = convertNullableType(typeRefSource, it, allTypeModifiers)
                DYNAMIC_TYPE -> firType = buildDynamicTypeRef {
                    source = typeRefSource
                    isMarkedNullable = true
                }
                INTERSECTION_TYPE -> firType =
                    buildErrorTypeRef {
                        source = typeRefSource
                        diagnostic = ConeSimpleDiagnostic("Intersection types are not supported yet", DiagnosticKind.Syntax)
                    }
            }
        }

        return firType
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseUserType
     */
    private fun convertUserType(
        typeRefSource: FirSourceElement,
        userType: LighterASTNode,
        isNullable: Boolean = false
    ): FirTypeRef {
        var simpleFirUserType: FirUserTypeRef? = null
        var identifier: String? = null
        var identifierSource: FirSourceElement? = null
        val firTypeArguments = mutableListOf<FirTypeProjection>()
        var typeArgumentsSource: FirSourceElement? = null
        userType.forEachChildren {
            when (it.tokenType) {
                USER_TYPE -> simpleFirUserType = convertUserType(typeRefSource, it) as? FirUserTypeRef //simple user type
                REFERENCE_EXPRESSION -> {
                    identifierSource = it.toFirSourceElement()
                    identifier = it.asText
                }
                TYPE_ARGUMENT_LIST -> {
                    typeArgumentsSource = it.toFirSourceElement()
                    firTypeArguments += convertTypeArguments(it)
                }
            }
        }

        if (identifier == null)
            return buildErrorTypeRef {
                source = typeRefSource
                diagnostic = ConeSimpleDiagnostic("Incomplete user type", DiagnosticKind.Syntax)
            }

        val qualifierPart = FirQualifierPartImpl(
            identifierSource!!,
            identifier.nameAsSafeName(),
            FirTypeArgumentListImpl(typeArgumentsSource ?: typeRefSource).apply {
                typeArguments += firTypeArguments
            }
        )

        return buildUserTypeRef {
            source = typeRefSource
            isMarkedNullable = isNullable
            qualifier.add(qualifierPart)
            simpleFirUserType?.qualifier?.let { this.qualifier.addAll(0, it) }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseTypeArgumentList
     */
    fun convertTypeArguments(typeArguments: LighterASTNode): List<FirTypeProjection> {
        return typeArguments.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                TYPE_PROJECTION -> container += convertTypeProjection(node)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.tryParseTypeArgumentList
     */
    private fun convertTypeProjection(typeProjection: LighterASTNode): FirTypeProjection {
        var modifiers = TypeProjectionModifier()
        lateinit var firType: FirTypeRef
        var isStarProjection = false
        typeProjection.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertTypeArgumentModifierList(it)
                TYPE_REFERENCE -> firType = convertType(it)
                MUL -> isStarProjection = true
            }
        }

        //annotations from modifiers must be ignored
        return if (isStarProjection) buildStarProjection { source = typeProjection.toFirSourceElement() }
        else buildTypeProjectionWithVariance {
            source = typeProjection.toFirSourceElement()
            typeRef = firType
            variance = modifiers.getVariance()
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseFunctionType
     */
    private fun convertFunctionType(
        typeRefSource: FirSourceElement,
        functionType: LighterASTNode,
        isNullable: Boolean = false,
        isSuspend: Boolean = false
    ): FirTypeRef {
        var receiverTypeReference: FirTypeRef? = null
        lateinit var returnTypeReference: FirTypeRef
        val valueParametersList = mutableListOf<ValueParameter>()
        functionType.forEachChildren {
            when (it.tokenType) {
                FUNCTION_TYPE_RECEIVER -> receiverTypeReference = convertReceiverType(it)
                VALUE_PARAMETER_LIST -> valueParametersList += convertValueParameters(it)
                TYPE_REFERENCE -> returnTypeReference = convertType(it)
            }
        }

        return buildFunctionTypeRef {
            source = typeRefSource
            isMarkedNullable = isNullable
            receiverTypeRef = receiverTypeReference
            returnTypeRef = returnTypeReference
            valueParameters += valueParametersList.map { it.firValueParameter }
            if (receiverTypeReference != null) {
                annotations += extensionFunctionAnnotation
            }
            this.isSuspend = isSuspend
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseValueParameterList
     */
    fun convertValueParameters(
        valueParameters: LighterASTNode,
        valueParameterDeclaration: ValueParameterDeclaration = ValueParameterDeclaration.OTHER
    ): List<ValueParameter> {
        return valueParameters.forEachChildrenReturnList { node, container ->
            when (node.tokenType) {
                VALUE_PARAMETER -> container += convertValueParameter(node, valueParameterDeclaration)
            }
        }
    }

    /**
     * @see org.jetbrains.kotlin.parsing.KotlinParsing.parseValueParameter
     */
    fun convertValueParameter(
        valueParameter: LighterASTNode,
        valueParameterDeclaration: ValueParameterDeclaration = ValueParameterDeclaration.OTHER
    ): ValueParameter {
        var modifiers = Modifier()
        var isVal = false
        var isVar = false
        var identifier: String? = null
        var firType: FirTypeRef? = null
        var firExpression: FirExpression? = null
        var destructuringDeclaration: DestructuringDeclaration? = null
        valueParameter.forEachChildren {
            when (it.tokenType) {
                MODIFIER_LIST -> modifiers = convertModifierList(it)
                VAL_KEYWORD -> isVal = true
                VAR_KEYWORD -> isVar = true
                IDENTIFIER -> identifier = it.asText
                TYPE_REFERENCE -> firType = convertType(it)
                DESTRUCTURING_DECLARATION -> destructuringDeclaration = convertDestructingDeclaration(it)
                else -> if (it.isExpression()) firExpression = expressionConverter.getAsFirExpression(it, "Should have default value")
            }
        }

        val name = convertValueParameterName(identifier.nameAsSafeName(), identifier, valueParameterDeclaration)
        val firValueParameter = buildValueParameter {
            source = valueParameter.toFirSourceElement()
            moduleData = baseModuleData
            origin = FirDeclarationOrigin.Source
            returnTypeRef = firType ?: implicitType
            this.name = name
            symbol = FirValueParameterSymbol(name)
            defaultValue = firExpression
            isCrossinline = modifiers.hasCrossinline()
            isNoinline = modifiers.hasNoinline()
            isVararg = modifiers.hasVararg()
            annotations += modifiers.annotations
        }
        return ValueParameter(isVal, isVar, modifiers, firValueParameter, destructuringDeclaration)
    }

    private val extensionFunctionAnnotation = buildAnnotation {
        annotationTypeRef = buildResolvedTypeRef {
            type = ConeClassLikeTypeImpl(
                ConeClassLikeLookupTagImpl(EXTENSION_FUNCTION_ANNOTATION),
                emptyArray(),
                false
            )
        }
        argumentMapping = FirEmptyAnnotationArgumentMapping
    }

    private fun <T> fillDanglingConstraintsTo(
        typeParameters: List<FirTypeParameter>,
        typeConstraints: List<TypeConstraint>,
        to: T
    ) where T : FirDeclaration, T : FirTypeParameterRefsOwner {
        val typeParamNames = typeParameters.map { it.name }.toSet()
        val result = typeConstraints.mapNotNull { constraint ->
            val name = constraint.identifier.nameAsSafeName()
            if (!typeParamNames.contains(name)) {
                DanglingTypeConstraint(name, constraint.source)
            } else {
                null
            }

        }
        if (result.isNotEmpty()) {
            to.danglingTypeConstraints = result
        }
    }
}
