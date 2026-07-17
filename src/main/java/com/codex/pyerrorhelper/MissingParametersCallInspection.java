package com.codex.pyerrorhelper;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public final class MissingParametersCallInspection extends LocalInspectionTool {
    private static final String MESSAGE =
            "这里传入的是 parameters 方法本身，优化器需要参数序列；请调用该方法，在末尾补上 ()";

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new PsiElementVisitor() {
            @Override
            public void visitElement(@NotNull PsiElement element) {
                String referenceText = element.getText();
                PsiElement parent = element.getParent();
                if (parent == null) {
                    return;
                }

                String surroundingText = findSurroundingCallText(element);
                if (MissingParametersCallDetector.isMissingCall(referenceText, parent.getText(), surroundingText)) {
                    holder.registerProblem(
                            element,
                            MESSAGE,
                            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                            new AddParametersCallQuickFix()
                    );
                    return;
                }
                super.visitElement(element);
            }
        };
    }

    private static String findSurroundingCallText(PsiElement element) {
        PsiElement current = element;
        for (int level = 0; level < 6 && current != null; level++) {
            String text = current.getText();
            if (text != null && text.contains("(") && text.contains("parameters")) {
                if (text.contains("optim.") || text.contains("torch.optim.")
                        || text.matches("(?s).*(?:Adam|AdamW|SGD|RMSprop|Adagrad|Adadelta|ASGD|LBFGS|NAdam|RAdam|SparseAdam)\\s*\\(.*")) {
                    return text;
                }
            }
            current = current.getParent();
        }
        return "";
    }

    private static final class AddParametersCallQuickFix implements LocalQuickFix {
        @Override
        public @NotNull String getFamilyName() {
            return "补上 parameters() 调用括号";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (!element.isValid() || !element.getText().endsWith(".parameters")) {
                return;
            }
            PsiFile file = element.getContainingFile();
            Document document = PsiDocumentManager.getInstance(project).getDocument(file);
            if (document == null) {
                return;
            }
            int insertionOffset = element.getTextRange().getEndOffset();
            WriteCommandAction.writeCommandAction(project, file)
                    .withName("补上 parameters() 调用括号")
                    .run(() -> {
                        if (insertionOffset <= document.getTextLength()
                                && (insertionOffset == document.getTextLength()
                                || document.getCharsSequence().charAt(insertionOffset) != '(')) {
                            document.insertString(insertionOffset, "()");
                            PsiDocumentManager.getInstance(project).commitDocument(document);
                        }
                    });
        }
    }
}
