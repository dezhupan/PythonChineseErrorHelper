package com.codex.pyerrorhelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PythonErrorAnalyzerSelfTest {
    public static void main(String[] args) throws Exception {
        testNameErrorAndProjectFrameSelection();
        testSyntaxError();
        testTorchReshapeTypeError();
        testMethodObjectRuntimeError();
        testSyntaxCaretPreciseHighlight();
        testUnsupportedOperandTracesPreviousAssignment();
        testUnsupportedOperandDoesNotGuessWithoutCountMeaning();
        testLinearShapeMismatchFindsConfigurationLine();
        testRnnInputMismatchFindsConfigurationLine();
        testConvolutionChannelMismatchFindsConfigurationLine();
        testBatchNormMismatchFindsConfigurationLine();
        testLayerNormMismatchFindsConfigurationLine();
        testPlainTextPlacesTranslationAfterOriginalMessage();
        testMissingParametersCallDetector();
        testNonPythonOutput();
        PythonErrorRootCauseCorpusTest.runAll();
        StateDictErrorCorpusTest.runAll();
        System.out.println("PythonErrorAnalyzerSelfTest: ALL TESTS PASSED");
    }

    private static void testNameErrorAndProjectFrameSelection() {
        String traceback = """
                Traceback (most recent call last):
                  File "D:\\demo\\main.py", line 8, in <module>
                    print(user_name)
                  File "D:\\demo\\.venv\\Lib\\site-packages\\demo.py", line 2, in call
                    return value
                NameError: name 'user_name' is not defined
                """;
        check(PythonErrorAnalyzer.looksLikePythonError(traceback), "应识别 Python traceback");
        ErrorAnalysis result = PythonErrorAnalyzer.analyze(traceback, "D:\\demo", "main");
        check("NameError".equals(result.exceptionType()), "应识别 NameError");
        check(result.filePath().endsWith("main.py"), "应优先选择项目内代码，而不是 site-packages");
        check(result.lineNumber() == 8, "应识别第 8 行");
        check(result.chineseExplanation().contains("user_name"), "中文解释应包含变量名");
    }

    private static void testSyntaxError() {
        String traceback = """
                  File "D:\\demo\\broken.py", line 3
                    if value > 0
                                ^
                SyntaxError: expected ':'
                """;
        ErrorAnalysis result = PythonErrorAnalyzer.analyze(traceback, "D:\\demo", "broken");
        check("SyntaxError".equals(result.exceptionType()), "应识别 SyntaxError");
        check(result.suggestions().stream().anyMatch(text -> text.contains("冒号")), "应提示补冒号");
    }

    private static void testNonPythonOutput() {
        check(!PythonErrorAnalyzer.looksLikePythonError("Process finished with exit code 1"),
                "普通退出信息不应触发助手");
    }

    private static void testMissingParametersCallDetector() {
        check(MissingParametersCallDetector.isMissingCall(
                        "model.parameters",
                        "(model.parameters, lr=0.0001)",
                        "optim.Adam(model.parameters, lr=0.0001)"),
                "优化器中的 model.parameters 应提示补调用括号");
        check(!MissingParametersCallDetector.isMissingCall(
                        "model.parameters",
                        "model.parameters()",
                        "optim.Adam(model.parameters(), lr=0.0001)"),
                "已经调用 parameters() 时不应误报");
        check(!MissingParametersCallDetector.isMissingCall(
                        "model.parameters",
                        "model.parameters",
                        "print(model.parameters)"),
                "非优化器场景不应误报");
    }

    private static void testTorchReshapeTypeError() {
        String traceback = """
                Traceback (most recent call last):
                  File "D:\\demo\\model.py", line 78, in forward
                    output = self.out(output.reshape(-1, output[-1]))
                TypeError: reshape(): argument 'shape' failed to unpack the object at pos 2 with error "type must be tuple of ints, but got Tensor"
                """;
        ErrorAnalysis result = PythonErrorAnalyzer.analyze(traceback, "D:\\demo", "model");
        check(result.suggestions().stream().anyMatch(text -> text.contains("output.shape[-1]")),
                "PyTorch reshape 错误应给出准确的维度写法");
        check("output[-1]".equals(result.highlightText()), "应只高亮错误的 Tensor 表达式");
        check("output.shape[-1]".equals(result.replacementText()), "应提供可直接理解的替换内容");
    }

    private static void testMethodObjectRuntimeError() {
        String traceback = """
                Traceback (most recent call last):
                  File "D:\\demo\\train.py", line 112, in <module>
                    optimizer = optim.Adam(model.parameters, lr=0.0001)
                TypeError: 'method' object is not iterable
                """;
        ErrorAnalysis result = PythonErrorAnalyzer.analyze(traceback, "D:\\demo", "train");
        check("model.parameters".equals(result.highlightText()), "运行时报 method 错误时应只标黄 model.parameters");
        check("model.parameters()".equals(result.replacementText()), "应明确提示补上调用括号");
    }

    private static void testSyntaxCaretPreciseHighlight() {
        String codeLine = "    value = calculate(1, 2";
        String markerLine = " ".repeat(codeLine.indexOf('(')) + "^";
        String traceback = "  File \"D:\\demo\\broken.py\", line 3\n"
                + codeLine + "\n" + markerLine + "\n"
                + "SyntaxError: '(' was never closed\n";
        ErrorAnalysis result = PythonErrorAnalyzer.analyze(traceback, "D:\\demo", "broken");
        check("(".equals(result.highlightText()), "有 Python 箭头范围时应只标黄箭头所指字符");
    }

    private static void testLinearShapeMismatchFindsConfigurationLine() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.rnn = nn.RNN(input_size=128, hidden_size=256)
                        self.out = nn.Linear(in_features=300, out_features=5703)
                    def forward(self, x):
                        return self.out(x)
                """;
        ErrorAnalysis result = analyzeTemporaryRuntimeError(
                source,
                6,
                "mat1 and mat2 shapes cannot be multiplied (32x256 and 300x5703)"
        );
        check(result.lineNumber() == 4, "矩阵维度错误应跳到 nn.Linear 配置行，而不是 forward 调用行");
        check(result.sourceLine().contains("in_features=300"), "应显示真正需要修改的 Linear 配置");
        check("300".equals(result.highlightText()), "应只标黄错误的 in_features 数值");
        check("256".equals(result.replacementText()), "应根据实际输入维度给出替换值 256");
    }

    private static void testUnsupportedOperandTracesPreviousAssignment() throws IOException {
        String source = """
                class LyricDataset(Dataset):
                    def __init__(self, corpus_idx, num_chars):
                        self.corpus_idx = corpus_idx
                        self.num_chars = num_chars
                        self.word_count = corpus_idx
                        self.num_words = self.word_count // self.num_chars
                """;
        ErrorAnalysis result = analyzeTemporaryPythonError(
                source,
                6,
                "TypeError",
                "unsupported operand type(s) for //: 'list' and 'int'"
        );
        check(result.lineNumber() == 5, "容器参与整数除法时应回溯到错误赋值行，而不是只停在运算行");
        check(result.sourceLine().contains("self.word_count = corpus_idx"), "应显示真正造成类型错误的赋值");
        check("corpus_idx".equals(result.highlightText()), "应只标黄错误赋入的列表变量");
        check("len(corpus_idx)".equals(result.replacementText()), "应建议恢复 len(...) 获取元素数量");
    }

    private static void testUnsupportedOperandDoesNotGuessWithoutCountMeaning() throws IOException {
        String source = """
                class Model:
                    def forward(self, values, divisor):
                        self.values = values
                        return self.values // divisor
                """;
        ErrorAnalysis result = analyzeTemporaryPythonError(
                source,
                4,
                "TypeError",
                "unsupported operand type(s) for //: 'list' and 'int'"
        );
        check(result.lineNumber() == 4, "变量没有数量含义时应保留报错行，不能武断建议 len(...)");
        check(result.replacementText().isBlank(), "证据不足时不应生成自动替换内容");
    }

    private static void testRnnInputMismatchFindsConfigurationLine() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.embedding = nn.Embedding(1000, 300)
                        self.rnn = nn.RNN(input_size=128, hidden_size=256)
                    def forward(self, x):
                        return self.rnn(self.embedding(x))
                """;
        ErrorAnalysis result = analyzeTemporaryRuntimeError(
                source,
                6,
                "input.size(-1) must be equal to input_size. Expected 128, got 300"
        );
        check(result.lineNumber() == 4, "RNN 输入维度错误应跳到 input_size 配置行");
        check("128".equals(result.highlightText()), "应只标黄错误的 input_size 数值");
        check("300".equals(result.replacementText()), "应给出实际输入宽度作为候选修改值");
    }

    private static void testConvolutionChannelMismatchFindsConfigurationLine() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.conv = nn.Conv2d(in_channels=3, out_channels=16, kernel_size=3)
                    def forward(self, x):
                        return self.conv(x)
                """;
        ErrorAnalysis result = analyzeTemporaryRuntimeError(
                source,
                5,
                "Given groups=1, weight of size [16, 3, 3, 3], expected input[4, 1, 28, 28] to have 3 channels, but got 1 channels instead"
        );
        check(result.lineNumber() == 3, "卷积通道错误应跳到 in_channels 配置行");
        check("3".equals(result.highlightText()), "应只标黄错误的 in_channels 数值");
        check("1".equals(result.replacementText()), "应给出实际输入通道数作为候选修改值");
        String definition = source.lines().toList().get(2);
        int expectedColumn = definition.indexOf("in_channels=3") + "in_channels=".length();
        check(result.highlightColumn() == expectedColumn,
                "同一行有多个数字 3 时，必须定位 in_channels 的 3，而不是 kernel_size 的 3");
    }

    private static void testBatchNormMismatchFindsConfigurationLine() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.norm = nn.BatchNorm2d(num_features=64)
                    def forward(self, x):
                        return self.norm(x)
                """;
        ErrorAnalysis result = analyzeTemporaryRuntimeError(
                source,
                5,
                "running_mean should contain 32 elements not 64"
        );
        check(result.lineNumber() == 3, "BatchNorm 错误应跳到 num_features 配置行");
        check("64".equals(result.highlightText()), "应只标黄错误的 num_features 数值");
        check("32".equals(result.replacementText()), "应给出实际特征数作为候选修改值");
    }

    private static void testLayerNormMismatchFindsConfigurationLine() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.norm = nn.LayerNorm(normalized_shape=[128])
                    def forward(self, x):
                        return self.norm(x)
                """;
        ErrorAnalysis result = analyzeTemporaryRuntimeError(
                source,
                5,
                "Given normalized_shape=[128], expected input with shape [*, 128], but got input of size[32, 64]"
        );
        check(result.lineNumber() == 3, "LayerNorm 错误应跳到 normalized_shape 配置行");
        check("128".equals(result.highlightText()), "应只标黄错误的 normalized_shape 数值");
        check("64".equals(result.replacementText()), "应给出实际最后一维作为候选修改值");
    }

    private static void testPlainTextPlacesTranslationAfterOriginalMessage() {
        String traceback = """
                Traceback (most recent call last):
                  File "D:\\demo\\main.py", line 2, in run
                    result = value / 0
                ZeroDivisionError: division by zero
                """;
        String text = PythonErrorAnalyzer.analyze(traceback, "D:\\demo", "main").toPlainText();
        int original = text.indexOf("ZeroDivisionError: division by zero");
        int translation = text.indexOf("原始信息翻译解释：");
        int location = text.indexOf("位置：");
        check(original >= 0 && translation > original && location > translation,
                "原始信息翻译解释应紧跟原始信息，并位于代码位置之前");
        check(!text.contains("中文说明："), "新版不应再保留重复的中文说明标题");
    }

    private static ErrorAnalysis analyzeTemporaryRuntimeError(String source,
                                                              int failingLine,
                                                              String message) throws IOException {
        return analyzeTemporaryPythonError(source, failingLine, "RuntimeError", message);
    }

    private static ErrorAnalysis analyzeTemporaryPythonError(String source,
                                                             int failingLine,
                                                             String exceptionType,
                                                             String message) throws IOException {
        Path file = Files.createTempFile("python-error-helper-", ".py");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            String traceback = "Traceback (most recent call last):\n"
                    + "  File \"" + file + "\", line " + failingLine + ", in forward\n"
                    + "    " + Files.readAllLines(file, StandardCharsets.UTF_8).get(failingLine - 1).strip() + "\n"
                    + exceptionType + ": " + message + "\n";
            return PythonErrorAnalyzer.analyze(traceback, file.getParent().toString(), "model");
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
