package com.intellij.refactoring.rename;

import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiNamedElement;
import com.intellij.psi.impl.light.LightElement;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.naming.AutomaticRenamer;
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory;
import com.intellij.refactoring.ui.ConflictsDialog;
import com.intellij.refactoring.util.CommonRefactoringUtil;
import com.intellij.refactoring.util.MoveRenameUsageInfo;
import com.intellij.refactoring.util.NonCodeUsageInfo;
import com.intellij.refactoring.util.RelatedUsageInfo;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.usageView.UsageViewUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class RenameProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance("#com.intellij.refactoring.rename.RenameProcessor");

  private final LinkedHashMap<PsiElement, String> myAllRenames = new LinkedHashMap<PsiElement, String>();

  private PsiElement myPrimaryElement;
  private String myNewName = null;

  private boolean mySearchInComments;
  private boolean mySearchTextOccurrences;
  private boolean myForceShowPreview;

  private String myCommandName;

  private NonCodeUsageInfo[] myNonCodeUsages = new NonCodeUsageInfo[0];
  private final List<AutomaticRenamerFactory> myRenamerFactories = new ArrayList<AutomaticRenamerFactory>();
  private final List<AutomaticRenamer> myRenamers = new ArrayList<AutomaticRenamer>();

  public RenameProcessor(Project project,
                         PsiElement element,
                         @NotNull @NonNls String newName,
                         boolean isSearchInComments,
                         boolean isSearchTextOccurrences) {
    super(project);
    myPrimaryElement = element;

    mySearchInComments = isSearchInComments;
    mySearchTextOccurrences = isSearchTextOccurrences;

    setNewName(newName);
  }

  public RenameProcessor(Project project) {
    this(project, null, "", false, false);
  }

  public Set<PsiElement> getElements() {
    return Collections.unmodifiableSet(myAllRenames.keySet());
  }

  public void addRenamerFactory(AutomaticRenamerFactory factory) {
    if (!myRenamerFactories.contains(factory)) {
      myRenamerFactories.add(factory);
    }
  }

  public void removeRenamerFactory(AutomaticRenamerFactory factory) {
    myRenamerFactories.remove(factory);
  }

  public void doRun() {
    prepareRenaming();

    super.doRun();
  }

  public void prepareRenaming() {
    final RenamePsiElementProcessor processor = RenamePsiElementProcessor.forElement(myPrimaryElement);
    processor.prepareRenaming(myPrimaryElement, myNewName, myAllRenames);
    myForceShowPreview = processor.forcesShowPreview();
  }

  @Nullable
  private String getHelpID() {
    return RenamePsiElementProcessor.forElement(myPrimaryElement).getHelpID(myPrimaryElement);
  }

  public boolean preprocessUsages(Ref<UsageInfo[]> refUsages) {
    UsageInfo[] usagesIn = refUsages.get();
    Map<PsiElement, String> conflicts = new HashMap<PsiElement, String>();

    conflicts.putAll(RenameUtil.getConflictDescriptions(usagesIn));
    RenamePsiElementProcessor.forElement(myPrimaryElement).findExistingNameConflicts(myPrimaryElement, myNewName, conflicts);
    if (!conflicts.isEmpty()) {
      ConflictsDialog conflictsDialog = new ConflictsDialog(myProject, conflicts);
      conflictsDialog.show();
      if (!conflictsDialog.isOK()) {
        if (conflictsDialog.isShowConflicts()) prepareSuccessful();
        return false;
      }
    }
    Set<UsageInfo> usagesSet = new HashSet<UsageInfo>(Arrays.asList(usagesIn));
    RenameUtil.removeConflictUsages(usagesSet);

    final List<UsageInfo> variableUsages = new ArrayList<UsageInfo>();
    if (!myRenamers.isEmpty()) {
      if (!findRenamedVariables(variableUsages)) return false;
    }

    if (!variableUsages.isEmpty()) {
      usagesSet.addAll(variableUsages);
      refUsages.set(usagesSet.toArray(new UsageInfo[usagesSet.size()]));
    }

    prepareSuccessful();
    return true;
  }

  private boolean findRenamedVariables(final List<UsageInfo> variableUsages) {
    for (final AutomaticRenamer automaticVariableRenamer : myRenamers) {
      if (!automaticVariableRenamer.hasAnythingToRename()) continue;
      final AutomaticRenamingDialog dialog = new AutomaticRenamingDialog(myProject, automaticVariableRenamer);
      dialog.show();
      if (!dialog.isOK()) return false;
    }

    for (final AutomaticRenamer renamer : myRenamers) {
      final List<? extends PsiNamedElement> variables = renamer.getElements();
      for (final PsiNamedElement variable : variables) {
        final String newName = renamer.getNewName(variable);
        if (newName != null) {
          addElement(variable, newName);
        }
      }
    }

    Runnable runnable = new Runnable() {
      public void run() {
        for (final AutomaticRenamer renamer : myRenamers) {
          renamer.findUsages(variableUsages, mySearchInComments, mySearchTextOccurrences);
        }
      }
    };

    return ProgressManager.getInstance()
      .runProcessWithProgressSynchronously(runnable, RefactoringBundle.message("searching.for.variables"), true, myProject);
  }

  public void addElement(@NotNull PsiElement element, @NotNull String newName) {
    myAllRenames.put(element, newName);
  }

  private void setNewName(@NotNull String newName) {
    if (myPrimaryElement == null) {
      myCommandName = RefactoringBundle.message("renaming.something");
      return;
    }

    myNewName = newName;
    myAllRenames.put(myPrimaryElement, newName);
    myCommandName = RefactoringBundle
      .message("renaming.0.1.to.2", UsageViewUtil.getType(myPrimaryElement), UsageViewUtil.getDescriptiveName(myPrimaryElement), newName);
  }

  protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
    return new RenameViewDescriptor(myAllRenames);
  }

  @NotNull
  public UsageInfo[] findUsages() {
    myRenamers.clear();
    ArrayList<UsageInfo> result = new ArrayList<UsageInfo>();

    List<PsiElement> elements = new ArrayList<PsiElement>(myAllRenames.keySet());
    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < elements.size(); i++) {
      PsiElement element = elements.get(i);
      final String newName = myAllRenames.get(element);
      final UsageInfo[] usages = RenameUtil.findUsages(element, newName, mySearchInComments, mySearchTextOccurrences, myAllRenames);
      final List<UsageInfo> usagesList = Arrays.asList(usages);
      result.addAll(usagesList);

      for (AutomaticRenamerFactory factory : myRenamerFactories) {
        if (factory.isApplicable(element)) {
          myRenamers.add(factory.createRenamer(element, newName, usagesList));
        }
      }

      for (AutomaticRenamerFactory factory : Extensions.getExtensions(AutomaticRenamerFactory.EP_NAME)) {
        if (factory.getOptionName() == null && factory.isApplicable(element)) {
          myRenamers.add(factory.createRenamer(element, newName, usagesList));
        }
      }
    }
    UsageInfo[] usageInfos = result.toArray(new UsageInfo[result.size()]);
    usageInfos = UsageViewUtil.removeDuplicatedUsages(usageInfos);
    return usageInfos;
  }

  protected void refreshElements(PsiElement[] elements) {
    LOG.assertTrue(elements.length > 0);
    if (myPrimaryElement != null) {
      myPrimaryElement = elements[0];
    }

    final Iterator<String> newNames = myAllRenames.values().iterator();
    LinkedHashMap<PsiElement, String> newAllRenames = new LinkedHashMap<PsiElement, String>();
    for (PsiElement resolved : elements) {
      newAllRenames.put(resolved, newNames.next());
    }
    myAllRenames.clear();
    myAllRenames.putAll(newAllRenames);
  }

  protected boolean isPreviewUsages(UsageInfo[] usages) {
    if (myForceShowPreview) return true;
    if (super.isPreviewUsages(usages)) return true;
    if (UsageViewUtil.hasNonCodeUsages(usages)) {
      WindowManager.getInstance().getStatusBar(myProject)
        .setInfo(RefactoringBundle.message("occurrences.found.in.comments.strings.and.non.java.files"));
      return true;
    }
    return false;
  }

  protected boolean markCommandAsComplex() {
    return false;
  }

  public void performRefactoring(UsageInfo[] usages) {
    String message = null;
    try {
      for (Map.Entry<PsiElement, String> entry : myAllRenames.entrySet()) {
        RenameUtil.checkRename(entry.getKey(), entry.getValue());
      }
    }
    catch (IncorrectOperationException e) {
      message = e.getMessage();
    }

    if (message != null) {
      CommonRefactoringUtil.showErrorMessage(RefactoringBundle.message("rename.title"), message, getHelpID(), myProject);
      return;
    }

    if (markCommandAsComplex()) {
      CommandProcessor.getInstance().markCurrentCommandAsComplex(myProject);
    }

    List<Runnable> postRenameCallbacks = new ArrayList<Runnable>();

    for (Map.Entry<PsiElement, String> entry : myAllRenames.entrySet()) {
      PsiElement element = entry.getKey();
      String newName = entry.getValue();

      final RefactoringElementListener elementListener = getTransaction().getElementListener(element);
      RenameUtil.doRename(element, newName, extractUsagesForElement(element, usages), myProject, elementListener);
      Runnable postRenameCallback = RenamePsiElementProcessor.forElement(element).getPostRenameCallback(element, newName, elementListener);
      if (postRenameCallback != null) {
        postRenameCallbacks.add(postRenameCallback);
      }
    }

    for (Runnable runnable : postRenameCallbacks) {
      runnable.run();
    }

    List<NonCodeUsageInfo> nonCodeUsages = new ArrayList<NonCodeUsageInfo>();
    for (UsageInfo usage : usages) {
      if (usage instanceof NonCodeUsageInfo) {
        nonCodeUsages.add((NonCodeUsageInfo)usage);
      }
    }
    myNonCodeUsages = nonCodeUsages.toArray(new NonCodeUsageInfo[nonCodeUsages.size()]);
  }

  protected void performPsiSpoilingRefactoring() {
    RenameUtil.renameNonCodeUsages(myProject, myNonCodeUsages);
  }

  protected String getCommandName() {
    return myCommandName;
  }

  private static UsageInfo[] extractUsagesForElement(PsiElement element, UsageInfo[] usages) {
    final ArrayList<UsageInfo> extractedUsages = new ArrayList<UsageInfo>(usages.length);
    for (UsageInfo usage : usages) {
      LOG.assertTrue(usage instanceof MoveRenameUsageInfo);
      if (usage.getReference() instanceof LightElement) {
        continue; //filter out implicit references (e.g. from derived class to super class' default constructor)
      }
      MoveRenameUsageInfo usageInfo = (MoveRenameUsageInfo)usage;
      if (usage instanceof RelatedUsageInfo) {
        if (((RelatedUsageInfo)usage).getRelatedElement() == element) {
          extractedUsages.add(usageInfo);
        }
      } else {
        //todo[yole] this line was not true for groovy implicit accessor methods, which should be added to extractedUsages
        PsiElement referenced = usageInfo.getReferencedElement();
        if (element.equals(referenced) || referenced != null && element == referenced.getNavigationElement()) {
          extractedUsages.add(usageInfo);
        }
      }
    }
    return extractedUsages.toArray(new UsageInfo[extractedUsages.size()]);
  }

  protected void prepareTestRun() {
    if (!PsiElementRenameHandler.canRename(myProject, null, myPrimaryElement)) return;
    prepareRenaming();
  }

  public Collection<String> getNewNames() {
    return myAllRenames.values();
  }

  public void setSearchInComments(boolean value) {
    mySearchInComments = value;
  }

  public void setSearchTextOccurrences(boolean searchTextOccurrences) {
    mySearchTextOccurrences = searchTextOccurrences;
  }

  public boolean isSearchInComments() {
    return mySearchInComments;
  }

  public boolean isSearchTextOccurrences() {
    return mySearchTextOccurrences;
  }

  public void setCommandName(final String commandName) {
    myCommandName = commandName;
  }
}
