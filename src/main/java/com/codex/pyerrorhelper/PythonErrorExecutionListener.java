package com.codex.pyerrorhelper;

import com.intellij.execution.ExecutionListener;
import com.intellij.execution.filters.HyperlinkInfo;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.codeInsight.hint.HintManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.MarkupModel;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import org.jetbrains.annotations.NotNull;

import java.awt.Color;
import java.awt.Font;

public final class PythonErrorExecutionListener implements ExecutionListener {
    private static final int MAX_CAPTURED_CHARACTERS = 1_000_000;
    private static final Key<HighlightHandle> ACTIVE_HIGHLIGHT = Key.create("python.chinese.error.active.highlight");

    @Override
    public void processStarted(@NotNull String executorId,
                               @NotNull ExecutionEnvironment environment,
                               @NotNull ProcessHandler handler) {
        Project project = environment.getProject();
        if (project.isDisposed()) {
            return;
        }

        String runName = environment.getRunProfile().getName();
        StringBuffer captured = new StringBuffer(8192);
        handler.addProcessListener(new ProcessListener() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                synchronized (captured) {
                    captured.append(event.getText());
                    if (captured.length() > MAX_CAPTURED_CHARACTERS) {
                        captured.delete(0, captured.length() - MAX_CAPTURED_CHARACTERS / 2);
                    }
                }
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                if (event.getExitCode() == 0 || project.isDisposed()) {
                    return;
                }
                String output;
                synchronized (captured) {
                    output = captured.toString();
                }
                if (!PythonErrorAnalyzer.looksLikePythonError(output)) {
                    return;
                }
                ErrorAnalysis analysis = PythonErrorAnalyzer.analyze(output, project.getBasePath(), runName);
                showInRunConsole(project, environment, handler, output, analysis);
            }
        });
    }

    private void showInRunConsole(@NotNull Project project,
                                  @NotNull ExecutionEnvironment environment,
                                  @NotNull ProcessHandler handler,
                                  @NotNull String originalOutput,
                                  @NotNull ErrorAnalysis analysis) {
        RunContentManager contentManager = RunContentManager.getInstance(project);
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (project.isDisposed()) {
                return;
            }
            RunContentDescriptor descriptor = contentManager.findContentDescriptor(environment.getExecutor(), handler);
            if (descriptor == null || !(descriptor.getExecutionConsole() instanceof ConsoleView console)) {
                return;
            }
            new ConsoleErrorSwitcher(project, console, originalOutput, analysis).showChinese();
        });
    }

    private static final class ConsoleErrorSwitcher {
        private final Project project;
        private final ConsoleView console;
        private final String originalOutput;
        private final ErrorAnalysis analysis;

        private ConsoleErrorSwitcher(Project project,
                                     ConsoleView console,
                                     String originalOutput,
                                     ErrorAnalysis analysis) {
            this.project = project;
            this.console = console;
            this.originalOutput = originalOutput;
            this.analysis = analysis;
        }

        private void showChinese() {
            console.clear();
            system("\n================ Python 中文报错助手 ================\n\n");
            normal("运行项：" + analysis.runName() + "\n");
            normal("分析范围：" + analysis.analysisScope() + "\n");
            error("错误类型：" + analysis.exceptionType());
            if (!analysis.originalMessage().isBlank()) {
                error("\n原始信息：" + analysis.originalMessage());
            }
            error("\n原始信息翻译解释：" + analysis.chineseExplanation());
            String locationLabel;
            if (analysis.tracedToRootCause()) {
                locationLabel = "已向上回溯的根因位置：";
            } else if (!analysis.highlightText().isBlank() || !analysis.replacementText().isBlank()) {
                locationLabel = "建议修改位置：";
            } else {
                locationLabel = "错误触发位置（根因可能在前面）：";
            }
            normal("\n\n" + locationLabel);
            if (isNavigable()) {
                console.printHyperlink(analysis.locationText(), new HyperlinkInfo() {
                    @Override
                    public void navigate(@NotNull Project ignored) {
                        navigateToError();
                    }
                });
                normal("  ← 点击可跳转并标黄\n");
            } else {
                normal(analysis.locationText() + "\n");
            }
            if (!analysis.sourceLine().isBlank()) {
                String sourceLabel = analysis.tracedToRootCause()
                        ? "回溯后最可能写错的代码："
                        : "最值得先检查的代码：";
                normal("\n" + sourceLabel + "\n    " + analysis.sourceLine() + "\n");
            }
            if (!analysis.replacementText().isBlank()) {
                normal("\n明确修改建议：\n    " + analysis.highlightText() + "  →  " + analysis.replacementText() + "\n");
                if (isNavigable() && !analysis.highlightText().isBlank()) {
                    console.printHyperlink("【确认后应用这条修改】", new HyperlinkInfo() {
                        @Override
                        public void navigate(@NotNull Project ignored) {
                            applySuggestedReplacement();
                        }
                    });
                    normal("  （修改后仍可按 Ctrl+Z 撤销）\n");
                }
            }
            normal("\n建议怎么改：\n");
            for (int i = 0; i < analysis.suggestions().size(); i++) {
                normal((i + 1) + ". " + analysis.suggestions().get(i) + "\n");
            }
            normal("\n");
            console.printHyperlink("【一键切换：查看原始报错】", new HyperlinkInfo() {
                @Override
                public void navigate(@NotNull Project ignored) {
                    showOriginal();
                }
            });
            system("\n\n完全离线分析，不会上传代码或报错信息。\n");
            system("=======================================================\n");
            console.scrollTo(0);
        }

        private void showOriginal() {
            console.clear();
            normal(originalOutput);
            if (!originalOutput.endsWith("\n")) {
                normal("\n");
            }
            normal("\n");
            console.printHyperlink("【一键切换：查看中文解释】", new HyperlinkInfo() {
                @Override
                public void navigate(@NotNull Project ignored) {
                    showChinese();
                }
            });
            normal("\n");
            console.scrollTo(Math.max(0, originalOutput.length()));
        }

        private boolean isNavigable() {
            if (analysis.filePath().isBlank() || analysis.lineNumber() <= 0) {
                return false;
            }
            return LocalFileSystem.getInstance().findFileByPath(analysis.filePath().replace('\\', '/')) != null;
        }

        private void navigateToError() {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                    analysis.filePath().replace('\\', '/'));
            if (file != null) {
                new OpenFileDescriptor(project, file, Math.max(0, analysis.lineNumber() - 1), 0).navigate(true);
                javax.swing.SwingUtilities.invokeLater(() -> highlightSuggestedCode(file));
            }
        }

        private void applySuggestedReplacement() {
            VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByPath(
                    analysis.filePath().replace('\\', '/'));
            if (file == null) {
                Messages.showWarningDialog(project, "找不到建议修改的文件。", "Python 中文报错助手");
                return;
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null || analysis.lineNumber() <= 0 || analysis.lineNumber() > document.getLineCount()) {
                Messages.showWarningDialog(project, "无法读取建议修改的代码行。", "Python 中文报错助手");
                return;
            }
            int line = analysis.lineNumber() - 1;
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText(new TextRange(lineStart, lineEnd));
            int relativeStart = findTargetOffset(lineText);
            if (relativeStart < 0) {
                Messages.showWarningDialog(
                        project,
                        "代码可能已经变化，未找到要替换的内容：“" + analysis.highlightText() + "”",
                        "Python 中文报错助手"
                );
                return;
            }
            int answer = Messages.showYesNoDialog(
                    project,
                    "文件：" + analysis.filePath() + "\n第 " + analysis.lineNumber() + " 行\n\n将："
                            + analysis.highlightText() + "\n改为：" + analysis.replacementText() + "\n\n确认应用吗？",
                    "确认应用修改",
                    Messages.getQuestionIcon()
            );
            if (answer != Messages.YES) {
                return;
            }
            int replaceStart = lineStart + relativeStart;
            int replaceEnd = replaceStart + analysis.highlightText().length();
            WriteCommandAction.runWriteCommandAction(
                    project,
                    () -> document.replaceString(replaceStart, replaceEnd, analysis.replacementText())
            );
            new OpenFileDescriptor(project, file, line, replaceStart - lineStart).navigate(true);
        }

        private void highlightSuggestedCode(@NotNull VirtualFile file) {
            Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (editor == null || !file.equals(FileDocumentManager.getInstance().getFile(editor.getDocument()))) {
                return;
            }

            Document document = editor.getDocument();
            int line = Math.max(0, Math.min(analysis.lineNumber() - 1, document.getLineCount() - 1));
            int lineStart = document.getLineStartOffset(line);
            int lineEnd = document.getLineEndOffset(line);
            String lineText = document.getText(new TextRange(lineStart, lineEnd));

            HighlightHandle previous = project.getUserData(ACTIVE_HIGHLIGHT);
            if (previous != null) {
                previous.remove();
                project.putUserData(ACTIVE_HIGHLIGHT, null);
            }

            String target = analysis.highlightText();
            if (target.isBlank()) {
                editor.getCaretModel().moveToOffset(lineStart);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                HintManager.getInstance().showInformationHint(
                        editor,
                        "已定位到相关代码行，但无法可靠确定单个表达式；请结合中文建议检查，未进行整行标黄"
                );
                return;
            }
            int relativeStart = findTargetOffset(lineText);
            if (relativeStart < 0) {
                editor.getCaretModel().moveToOffset(lineStart);
                editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
                HintManager.getInstance().showInformationHint(
                        editor,
                        "代码可能已发生变化，未找到需要标黄的表达式：“" + target + "”"
                );
                return;
            }
            int highlightStart = lineStart + relativeStart;
            int highlightEnd = highlightStart + target.length();

            TextAttributes attributes = new TextAttributes(
                    null,
                    new JBColor(new Color(255, 235, 120), new Color(110, 85, 0)),
                    new JBColor(new Color(210, 155, 0), new Color(235, 190, 40)),
                    null,
                    Font.BOLD
            );
            MarkupModel markupModel = editor.getMarkupModel();
            RangeHighlighter highlighter = markupModel.addRangeHighlighter(
                    highlightStart,
                    Math.max(highlightStart + 1, highlightEnd),
                    HighlighterLayer.SELECTION - 1,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE
            );
            HighlightHandle handle = new HighlightHandle(markupModel, highlighter, document);
            DocumentListener clearOnEdit = new DocumentListener() {
                @Override
                public void documentChanged(@NotNull DocumentEvent event) {
                    handle.remove();
                    if (project.getUserData(ACTIVE_HIGHLIGHT) == handle) {
                        project.putUserData(ACTIVE_HIGHLIGHT, null);
                    }
                }
            };
            handle.attach(clearOnEdit);
            document.addDocumentListener(clearOnEdit, project);
            project.putUserData(ACTIVE_HIGHLIGHT, handle);

            editor.getCaretModel().moveToOffset(highlightStart);
            editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
            String message = analysis.replacementText().isBlank()
                    ? "请优先检查黄色高亮处：" + analysis.suggestions().get(0)
                    : "建议修改：将 “" + analysis.highlightText() + "” 改为 “" + analysis.replacementText() + "”";
            HintManager.getInstance().showInformationHint(editor, message);
        }

        private int findTargetOffset(String lineText) {
            String target = analysis.highlightText();
            if (target.isBlank()) {
                return -1;
            }
            int relativeStart = analysis.highlightColumn();
            if (relativeStart < 0
                    || relativeStart + target.length() > lineText.length()
                    || !lineText.regionMatches(relativeStart, target, 0, target.length())) {
                relativeStart = lineText.lastIndexOf(target);
            }
            return relativeStart;
        }

        private void normal(String text) {
            console.print(text, ConsoleViewContentType.NORMAL_OUTPUT);
        }

        private void error(String text) {
            console.print(text, ConsoleViewContentType.ERROR_OUTPUT);
        }

        private void system(String text) {
            console.print(text, ConsoleViewContentType.SYSTEM_OUTPUT);
        }
    }

    private static final class HighlightHandle {
        private final MarkupModel markupModel;
        private final RangeHighlighter highlighter;
        private final Document document;
        private DocumentListener documentListener;

        private HighlightHandle(MarkupModel markupModel, RangeHighlighter highlighter, Document document) {
            this.markupModel = markupModel;
            this.highlighter = highlighter;
            this.document = document;
        }

        private void attach(DocumentListener listener) {
            documentListener = listener;
        }

        private void remove() {
            if (documentListener != null) {
                document.removeDocumentListener(documentListener);
                documentListener = null;
            }
            if (highlighter.isValid()) {
                markupModel.removeHighlighter(highlighter);
            }
        }
    }
}
