/*
 * Copyright 2000-2016 JetBrains s.r.o.
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
package com.intellij.debugger.impl;

import com.intellij.debugger.SourcePosition;
import com.intellij.debugger.engine.CompoundPositionManager;
import com.intellij.debugger.engine.DebugProcessImpl;
import com.intellij.debugger.engine.LambdaMethodFilter;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.debugger.engine.evaluation.EvaluateException;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.navigation.NavigationItem;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiCompiledFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.sun.jdi.*;
import org.jetbrains.annotations.TestOnly;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * @author egor
 */
public class SourceCodeChecker {
  private static final Logger LOG = Logger.getInstance("#com.intellij.debugger.impl.SourceCodeChecker");

  private SourceCodeChecker() {
  }

  public static void checkSource(DebuggerContextImpl debuggerContext) {
    if (!Registry.is("debugger.check.source")) {
      return;
    }
    SuspendContextImpl suspendContext = debuggerContext.getSuspendContext();
    if (suspendContext == null) {
      return;
    }
    suspendContext.getDebugProcess().getManagerThread().schedule(new SuspendContextCommandImpl(suspendContext) {
      @Override
      public Priority getPriority() {
        return Priority.LOW;
      }

      @Override
      public void contextAction() throws Exception {
        try {
          StackFrameProxyImpl frameProxy = debuggerContext.getFrameProxy();
          if (frameProxy == null) {
            return;
          }
          Location location = frameProxy.location();
          check(location, debuggerContext.getSourcePosition(), suspendContext.getDebugProcess().getProject());
          //checkAllClasses(debuggerContext);
        }
        catch (EvaluateException e) {
          LOG.info(e);
        }
        catch (AbsentInformationException ignore) {
        }
      }
    });
  }

  private static ThreeState check(Location location, SourcePosition position, Project project) throws AbsentInformationException {
    Method method = location.method();
    // for now skip constructors, bridges, lambdas etc.
    if (method.isConstructor() ||
        method.isSynthetic() ||
        method.isBridge() ||
        method.isStaticInitializer() ||
        (method.declaringType() instanceof ClassType && ((ClassType)method.declaringType()).isEnum()) ||
        LambdaMethodFilter.isLambdaName(method.name())) {
      return ThreeState.UNSURE;
    }
    List<Location> locations = method.allLineLocations();
    if (ContainerUtil.isEmpty(locations)) {
      return ThreeState.UNSURE;
    }
    if (position != null) {
      return ApplicationManager.getApplication().runReadAction((Computable<ThreeState>)() -> {
        PsiFile psiFile = position.getFile();
        if (!psiFile.getLanguage().isKindOf(JavaLanguage.INSTANCE)) { // only for java for now
          return ThreeState.UNSURE;
        }
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
          return ThreeState.UNSURE;
        }
        boolean res = false;
        PsiElement psiMethod = DebuggerUtilsEx.getContainingMethod(position);
        if (psiMethod != null) {
          TextRange range = psiMethod.getTextRange();
          int startLine = document.getLineNumber(range.getStartOffset()) + 1;
          int endLine = document.getLineNumber(range.getEndOffset()) + 1;
          res = getLinesStream(locations, psiFile).allMatch(line -> startLine <= line && line <= endLine);
          if (!res) {
            LOG.debug("Source check failed: Method " + method.name() + ", source: " + ((NavigationItem)psiMethod).getName() +
                      "\nLines: " + getLinesStream(locations, psiFile).mapToObj(Integer::toString).collect(Collectors.joining(", ")) +
                      "\nExpected range: " + startLine + "-" + endLine
            );
          }
        }
        else {
          LOG.debug("Source check failed: method " + method.name() + " not found in sources");
        }
        if (!res) {
          XDebugSessionImpl.NOTIFICATION_GROUP.createNotification("Source code does not match the bytecode", NotificationType.WARNING)
            .notify(project);
          return ThreeState.NO;
        }
        return ThreeState.YES;
      });
    }
    return ThreeState.YES;
  }

  private static IntStream getLinesStream(List<Location> locations, PsiFile psiFile) {
    IntStream stream = locations.stream().mapToInt(Location::lineNumber);
    if (psiFile instanceof PsiCompiledFile) {
      stream = stream.map(line -> DebuggerUtilsEx.bytecodeToSourceLine(psiFile, line));
    }
    return stream.filter(line -> line >= 0);
  }

  @TestOnly
  @SuppressWarnings("UseOfSystemOutOrSystemErr")
  private static void checkAllClasses(DebuggerContextImpl debuggerContext) {
    DebugProcessImpl process = debuggerContext.getDebugProcess();
    @SuppressWarnings("ConstantConditions")
    VirtualMachine machine = process.getVirtualMachineProxy().getVirtualMachine();
    CompoundPositionManager positionManager = process.getPositionManager();
    List<ReferenceType> types = machine.allClasses();
    System.out.println("Checking " + types.size() + " classes");
    for (ReferenceType type : types) {
      try {
        for (Location loc : type.allLineLocations()) {
          SourcePosition position =
            ApplicationManager.getApplication().runReadAction((Computable<SourcePosition>)() -> positionManager.getSourcePosition(loc));
          if (position == null ||
              (position.getFile() instanceof PsiCompiledFile &&
               DebuggerUtilsEx.bytecodeToSourceLine(position.getFile(), loc.lineNumber()) == -1)) {
            continue;
          }
          if (check(loc, position, process.getProject()) == ThreeState.NO) {
            System.out.println("failed " + type);
            break;
          }
        }
      }
      catch (AbsentInformationException ignored) {
      }
    }
    System.out.println("Done checking");
  }
}
