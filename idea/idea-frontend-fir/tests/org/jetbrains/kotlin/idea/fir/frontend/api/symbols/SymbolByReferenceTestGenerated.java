/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.fir.frontend.api.symbols;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link GenerateNewCompilerTests.kt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("idea/idea-frontend-fir/testData/symbols/symbolByReference")
@TestDataPath("$PROJECT_ROOT")
public class SymbolByReferenceTestGenerated extends AbstractSymbolByReferenceTest {
    @Test
    @TestMetadata("accessorField.kt")
    public void testAccessorField() throws Exception {
        runTest("idea/idea-frontend-fir/testData/symbols/symbolByReference/accessorField.kt");
    }

    @Test
    public void testAllFilesPresentInSymbolByReference() throws Exception {
        KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("idea/idea-frontend-fir/testData/symbols/symbolByReference"), Pattern.compile("^(.+)\\.kt$"), null, true);
    }

    @Test
    @TestMetadata("constructorViaTypeAlias.kt")
    public void testConstructorViaTypeAlias() throws Exception {
        runTest("idea/idea-frontend-fir/testData/symbols/symbolByReference/constructorViaTypeAlias.kt");
    }

    @Test
    @TestMetadata("samConstructor.kt")
    public void testSamConstructor() throws Exception {
        runTest("idea/idea-frontend-fir/testData/symbols/symbolByReference/samConstructor.kt");
    }
}
