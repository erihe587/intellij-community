// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.siyeh.ig.junit;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ipp.junit.ConvertJUnit3TestCaseToJUnit4Predicate;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class Junit4ConverterInspection extends BaseInspection {

  @Override
  public boolean shouldInspect(PsiFile file) {
    if (PsiUtil.isLanguageLevel5OrHigher(file)) return true;
    return super.shouldInspect(file);
  }

  @Override
  protected @NotNull String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("convert.junit3.test.case.error.string");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new BaseInspectionVisitor() {
      @Override
      public void visitClass(PsiClass aClass) {
        super.visitClass(aClass);
        boolean possibleToConvert = new ConvertJUnit3TestCaseToJUnit4Predicate().satisfiedBy(aClass);
        if (possibleToConvert) {
          registerClassError(aClass);
        }
      }
    };
  }

  @Override
  protected @Nullable InspectionGadgetsFix buildFix(Object... infos) {
    return new InspectionGadgetsFix() {
      @Override
      protected void doFix(Project project, ProblemDescriptor descriptor) {
        PsiClass pClass = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class);
        JUnit4AnnotatedMethodInJUnit3TestCaseInspection.convertJUnit3ClassToJUnit4(pClass);
      }

      @Override
      public @NotNull String getFamilyName() {
        return InspectionGadgetsBundle.message("convert.junit3.test.case.family.name");
      }
    };
  }
}
