/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.codeInsight;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.PsiModificationTrackerImpl;
import com.intellij.psi.util.PsiTreeUtil;
import kotlin.jvm.functions.Function1;
import org.jetbrains.kotlin.idea.caches.resolve.ResolutionUtils;
import org.jetbrains.kotlin.idea.caches.trackers.KotlinCodeBlockModificationListenerKt;
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade;
import org.jetbrains.kotlin.idea.test.DirectiveBasedActionUtils;
import org.jetbrains.kotlin.idea.test.KotlinLightCodeInsightFixtureTestCase;
import org.jetbrains.kotlin.psi.*;
import org.jetbrains.kotlin.resolve.BindingContext;
import org.jetbrains.kotlin.resolve.diagnostics.Diagnostics;
import org.jetbrains.kotlin.resolve.lazy.ResolveSession;
import org.jetbrains.kotlin.test.InTextDirectivesUtils;

import java.io.IOException;

public abstract class AbstractOutOfBlockModificationTest extends KotlinLightCodeInsightFixtureTestCase {

    public static final String OUT_OF_CODE_BLOCK_DIRECTIVE = "OUT_OF_CODE_BLOCK:";
    public static final String SKIP_ANALYZE_CHECK_DIRECTIVE = "SKIP_ANALYZE_CHECK";
    public static final String TYPE_DIRECTIVE = "TYPE:";

    protected void doTest(String unused) throws IOException {
        myFixture.configureByFile(fileName());

        boolean expectedOutOfBlock = getExpectedOutOfBlockResult();
        boolean isSkipCheckDefined = InTextDirectivesUtils.isDirectiveDefined(myFixture.getFile().getText(), SKIP_ANALYZE_CHECK_DIRECTIVE);

        assertTrue("It's allowed to skip check with analyze only for tests where out-of-block is expected",
                   !isSkipCheckDefined || expectedOutOfBlock);


        PsiModificationTrackerImpl tracker =
                (PsiModificationTrackerImpl) PsiManager.getInstance(myFixture.getProject()).getModificationTracker();

        PsiElement element = myFixture.getFile().findElementAt(myFixture.getCaretOffset());
        assertNotNull("Should be valid element", element);

        KtFile file = (KtFile) getFile();

        long oobBeforeType = KotlinCodeBlockModificationListenerKt.getOutOfBlockModificationCount(file);
        long modificationCountBeforeType = tracker.getModificationCount();

        myFixture.type(getStringToType());
        PsiDocumentManager.getInstance(myFixture.getProject()).commitDocument(myFixture.getDocument(myFixture.getFile()));

        long oobAfterCount = KotlinCodeBlockModificationListenerKt.getOutOfBlockModificationCount(file);
        long modificationCountAfterType = tracker.getModificationCount();

        assertTrue("Modification tracker should always be changed after type", modificationCountBeforeType != modificationCountAfterType);

        assertEquals("Result for out of block test is differs from expected on element in file:\n"
                 + FileUtil.loadFile(testDataFile()),
                 expectedOutOfBlock, oobBeforeType != oobAfterCount);

        if (!isSkipCheckDefined) {
            checkOOBWithDescriptorsResolve(expectedOutOfBlock);
        }

    }

    private void checkOOBWithDescriptorsResolve(boolean expectedOutOfBlock) {
        ApplicationManager.getApplication().runReadAction(
                () -> ((PsiModificationTrackerImpl) PsiManager.getInstance(myFixture.getProject()).getModificationTracker())
                        .incOutOfCodeBlockModificationCounter());

        PsiElement updateElement = myFixture.getFile().findElementAt(myFixture.getCaretOffset() - 1);
        KtExpression ktExpression = PsiTreeUtil.getParentOfType(updateElement, KtExpression.class, false);
        KtDeclaration ktDeclaration = PsiTreeUtil.getParentOfType(updateElement, KtDeclaration.class, false);
        KtElement ktElement = ktExpression != null ? ktExpression : ktDeclaration;

        if (ktElement == null) return;

        ResolutionFacade facade = ResolutionUtils.getResolutionFacade(ktElement.getContainingKtFile());
        ResolveSession session = facade.getFrontendService(ResolveSession.class);
        session.forceResolveAll();

        BindingContext context = session.getBindingContext();

        if (ktExpression != null && ktExpression != ktDeclaration) {
            @SuppressWarnings("ConstantConditions")
            boolean expressionProcessed = context.get(
                    BindingContext.PROCESSED,
                    ktExpression instanceof KtFunctionLiteral ? (KtLambdaExpression) ktExpression.getParent() : ktExpression) == Boolean.TRUE;

            assertEquals("Expected out-of-block should result expression analyzed and vise versa", expectedOutOfBlock,
                         expressionProcessed);
        }
        else {
            boolean declarationProcessed = context.get(BindingContext.DECLARATION_TO_DESCRIPTOR, ktDeclaration) != null;
            assertEquals("Expected out-of-block should result declaration analyzed and vise versa", expectedOutOfBlock,
                         declarationProcessed);
        }

        checkForUnexpectedErrors();
    }

    private void checkForUnexpectedErrors() {
        DirectiveBasedActionUtils.INSTANCE.checkForUnexpectedErrors((KtFile) myFixture.getFile());
    }

    private String getStringToType() {
        String text = myFixture.getDocument(myFixture.getFile()).getText();
        String typeDirectives = InTextDirectivesUtils.findStringWithPrefixes(text, TYPE_DIRECTIVE);

        return typeDirectives != null ? StringUtil.unescapeStringCharacters(typeDirectives) : "a";
    }

    private boolean getExpectedOutOfBlockResult() {
        String text = myFixture.getDocument(myFixture.getFile()).getText();

        String outOfCodeBlockDirective = InTextDirectivesUtils.findStringWithPrefixes(text, OUT_OF_CODE_BLOCK_DIRECTIVE);
        assertNotNull(fileName() +
                      ": Expectation of code block result test should be configured with " +
                      "\"// " + OUT_OF_CODE_BLOCK_DIRECTIVE + " TRUE\" or " +
                      "\"// " + OUT_OF_CODE_BLOCK_DIRECTIVE + " FALSE\" directive in the file",
                      outOfCodeBlockDirective);
        return Boolean.parseBoolean(outOfCodeBlockDirective);
    }
}
