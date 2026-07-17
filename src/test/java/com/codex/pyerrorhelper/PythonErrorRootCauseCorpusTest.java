package com.codex.pyerrorhelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class PythonErrorRootCauseCorpusTest {
    private PythonErrorRootCauseCorpusTest() {
    }

    static void runAll() throws Exception {
        testBuiltinFunctionShadowing();
        testCustomCallableDoesNotGuess();
        testNonSubscriptableTracesAssignment();
        testWrongListIndexType();
        testNoneNotIterableTracesAssignment();
        testZeroDivisionTracesAssignment();
        testNoneAttributeTracesReturnSource();
        testAttributeTypoUsesPythonSuggestion();
        testNameTypoUsesPythonSuggestion();
        testUnexpectedKeywordHighlightsKeyword();
        testFileNotFoundTracesPathConfiguration();
        testKeyErrorHighlightsMissingKey();
        testValueUnpackHighlightsLeftSide();
        testCrossEntropyBatchMismatch();
        testCrossEntropyTargetOutOfBounds();
        testEmbeddingIndexOutOfRange();
        testTensorDtypeTracesBadConversion();
        testExpectedLongHighlightsLabels();
        testCudaNumpySuggestsCpuConversion();
        testRequiresGradNumpySuggestsDetach();
        testInvalidTensorShapeHighlightsShape();
        testTensorItemNeedsSingleElement();
        testConv2dInputRank();
        testStackSizeMismatch();
        testBatchNormSingleSample();
        testTensorSizeMismatchExplainsDimension();
        testDeviceMismatchExplainsDevices();
        testMultilineLinearKeywordConfiguration();
        testMultilineLinearPositionalConfiguration();
    }

    private static void testBuiltinFunctionShadowing() throws IOException {
        String source = """
                def run(values):
                    len = 5
                    return len(values)
                """;
        ErrorAnalysis result = analyze(source, 3, "TypeError", "'int' object is not callable");
        check(result.lineNumber() == 2, "内置函数被覆盖时应回溯到同名赋值");
        check(result.tracedToRootCause(), "内置函数覆盖应标记为已回溯根因");
        check("len".equals(result.highlightText()), "应标出覆盖内置函数的变量名");
        check("len_value".equals(result.replacementText()), "应给出避免覆盖内置函数的重命名建议");
    }

    private static void testCustomCallableDoesNotGuess() throws IOException {
        String source = """
                def run():
                    handler = 5
                    return handler()
                """;
        ErrorAnalysis result = analyze(source, 3, "TypeError", "'int' object is not callable");
        check(result.lineNumber() == 3, "自定义名称缺少定义上下文时不应武断回溯");
        check(!result.tracedToRootCause(), "低置信度场景不能标成已找到根因");
    }

    private static void testNonSubscriptableTracesAssignment() throws IOException {
        String source = """
                def run():
                    items = 1
                    return items[0]
                """;
        ErrorAnalysis result = analyze(source, 3, "TypeError", "'int' object is not subscriptable");
        check(result.lineNumber() == 2, "不可索引类型应回溯到产生错误类型的赋值");
        check("1".equals(result.highlightText()), "应标出错误赋入的值");
    }

    private static void testWrongListIndexType() throws IOException {
        String source = """
                def run(items):
                    return items["name"]
                """;
        ErrorAnalysis result = analyze(
                source, 2, "TypeError",
                "list indices must be integers or slices, not str"
        );
        check("\"name\"".equals(result.highlightText()), "列表索引类型错误应标出错误索引");
        check(result.chineseExplanation().contains("列表和字符串"), "应解释列表与字典索引方式的区别");
    }

    private static void testNoneNotIterableTracesAssignment() throws IOException {
        String source = """
                def run():
                    records = load_records()
                    for record in records:
                        print(record)
                """;
        ErrorAnalysis result = analyze(source, 3, "TypeError", "'NoneType' object is not iterable");
        check(result.lineNumber() == 2, "None 不可迭代应回溯到函数返回值赋值");
        check("load_records()".equals(result.highlightText()), "应标出可能遗漏 return 的调用");
    }

    private static void testZeroDivisionTracesAssignment() throws IOException {
        String source = """
                def run(total):
                    divisor = 0
                    return total / divisor
                """;
        ErrorAnalysis result = analyze(source, 3, "ZeroDivisionError", "division by zero");
        check(result.lineNumber() == 2, "除零错误应回溯到除数被赋零的位置");
        check("0".equals(result.highlightText()), "应只标出零值");
    }

    private static void testNoneAttributeTracesReturnSource() throws IOException {
        String source = """
                def run():
                    user = find_user()
                    return user.name
                """;
        ErrorAnalysis result = analyze(source, 3, "AttributeError", "'NoneType' object has no attribute 'name'");
        check(result.lineNumber() == 2, "None 属性错误应回溯到返回 None 的调用赋值");
        check("find_user()".equals(result.highlightText()), "应标出产生 None 的函数调用");
    }

    private static void testAttributeTypoUsesPythonSuggestion() throws IOException {
        String source = """
                def run(items):
                    return items.apend(1)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "AttributeError",
                "'list' object has no attribute 'apend'. Did you mean: 'append'?"
        );
        check("apend".equals(result.highlightText()), "属性拼写错误应标出错误属性");
        check("append".equals(result.replacementText()), "应使用 Python 提供的属性候选");
    }

    private static void testNameTypoUsesPythonSuggestion() throws IOException {
        String source = """
                def run(user_name):
                    return usre_name
                """;
        ErrorAnalysis result = analyze(
                source, 2, "NameError",
                "name 'usre_name' is not defined. Did you mean: 'user_name'?"
        );
        check("usre_name".equals(result.highlightText()), "名称拼写错误应精确标出");
        check("user_name".equals(result.replacementText()), "应使用 Python 提供的名称候选");
    }

    private static void testUnexpectedKeywordHighlightsKeyword() throws IOException {
        String source = """
                def run(model):
                    return model.fit(epochs=2, learing_rate=0.01)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "TypeError",
                "Model.fit() got an unexpected keyword argument 'learing_rate'"
        );
        check("learing_rate".equals(result.highlightText()), "未知关键字参数应只标出参数名");
    }

    private static void testFileNotFoundTracesPathConfiguration() throws IOException {
        String source = """
                def run():
                    data_path = "data/missing.csv"
                    return pd.read_csv(data_path)
                """;
        ErrorAnalysis result = analyze(
                source, 3, "FileNotFoundError",
                "[Errno 2] No such file or directory: 'data/missing.csv'"
        );
        check(result.lineNumber() == 2, "文件不存在应回溯到路径配置行");
        check("\"data/missing.csv\"".equals(result.highlightText()), "应标出配置的完整路径文本");
    }

    private static void testKeyErrorHighlightsMissingKey() throws IOException {
        String source = """
                def run(config):
                    return config["learning_rate"]
                """;
        ErrorAnalysis result = analyze(source, 2, "KeyError", "'learning_rate'");
        check("learning_rate".equals(result.highlightText()), "KeyError 应标出缺失键名");
    }

    private static void testValueUnpackHighlightsLeftSide() throws IOException {
        String source = """
                def run():
                    first, second, third = get_pair()
                """;
        ErrorAnalysis result = analyze(
                source, 2, "ValueError",
                "not enough values to unpack (expected 3, got 2)"
        );
        check("first, second, third".equals(result.highlightText()), "拆包数量错误应标出接收变量部分");
    }

    private static void testCrossEntropyBatchMismatch() throws IOException {
        String source = """
                def run(criterion, output, labels):
                    return criterion(output, labels)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "ValueError",
                "Expected input batch_size (32) to match target batch_size (64)."
        );
        check("labels".equals(result.highlightText()), "损失函数批大小不一致应标出标签参数");
        check(result.chineseExplanation().contains("32") && result.chineseExplanation().contains("64"),
                "中文说明应包含实际批大小");
    }

    private static void testCrossEntropyTargetOutOfBounds() throws IOException {
        String source = """
                def run(criterion, output, labels):
                    return criterion(output, labels)
                """;
        ErrorAnalysis result = analyze(source, 2, "IndexError", "Target 5 is out of bounds.");
        check("labels".equals(result.highlightText()), "类别标签越界应标出标签参数");
        check(result.chineseExplanation().contains("0 到 类别数-1"), "应说明分类标签合法范围");
    }

    private static void testEmbeddingIndexOutOfRange() throws IOException {
        String source = """
                def run(self, token_ids):
                    return self.embedding(token_ids)
                """;
        ErrorAnalysis result = analyze(source, 2, "IndexError", "index out of range in self");
        check("token_ids".equals(result.highlightText()), "Embedding 越界应标出输入索引");
        check(result.chineseExplanation().contains("num_embeddings"), "应解释 Embedding 索引范围");
    }

    private static void testTensorDtypeTracesBadConversion() throws IOException {
        String source = """
                def run(self, x):
                    x = x.double()
                    return self.fc(x)
                """;
        ErrorAnalysis result = analyze(
                source, 3, "RuntimeError",
                "mat1 and mat2 must have the same dtype, but got Double and Float"
        );
        check(result.lineNumber() == 2, "dtype 不一致应回溯到显式错误转换");
        check("x.double()".equals(result.highlightText()), "应标出错误 dtype 转换");
        check("x.float()".equals(result.replacementText()), "应建议与模型权重一致的转换");
    }

    private static void testExpectedLongHighlightsLabels() throws IOException {
        String source = """
                def run(criterion, logits, labels):
                    return criterion(logits, labels)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "expected scalar type Long but found Float"
        );
        check("labels".equals(result.highlightText()), "分类标签 dtype 错误应标出标签");
        check("labels.long()".equals(result.replacementText()), "应建议把类别标签转换为 long");
    }

    private static void testCudaNumpySuggestsCpuConversion() throws IOException {
        String source = """
                def run(output):
                    return output.numpy()
                """;
        ErrorAnalysis result = analyze(
                source, 2, "TypeError",
                "can't convert cuda:0 device type tensor to numpy. Use Tensor.cpu() to copy the tensor to host memory first."
        );
        check("output.numpy()".equals(result.highlightText()), "CUDA Tensor 转 NumPy 应标出完整调用");
        check("output.detach().cpu().numpy()".equals(result.replacementText()), "应给出安全的 CPU 转换顺序");
    }

    private static void testRequiresGradNumpySuggestsDetach() throws IOException {
        String source = """
                def run(output):
                    return output.numpy()
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "Can't call numpy() on Tensor that requires grad. Use tensor.detach().numpy() instead."
        );
        check("output.numpy()".equals(result.highlightText()), "带梯度 Tensor 转 NumPy 应标出完整调用");
        check("output.detach().numpy()".equals(result.replacementText()), "应建议先 detach");
    }

    private static void testInvalidTensorShapeHighlightsShape() throws IOException {
        String source = """
                def run(x):
                    return x.reshape(-1, 128)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "shape '[-1, 128]' is invalid for input of size 1000"
        );
        check("-1, 128".equals(result.highlightText()), "非法 reshape 应标出目标形状参数");
        check(result.chineseExplanation().contains("1000"), "应说明原张量元素总数");
    }

    private static void testTensorItemNeedsSingleElement() throws IOException {
        String source = """
                def run(losses):
                    return losses.item()
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "a Tensor with 4 elements cannot be converted to Scalar"
        );
        check("losses.item()".equals(result.highlightText()), "多元素 Tensor 调 item 应标出调用");
        check(result.chineseExplanation().contains("4 个元素"), "应说明 item 失败的元素数量");
    }

    private static void testConv2dInputRank() throws IOException {
        String source = """
                def run(self, images):
                    return self.conv(images)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "Expected 3D (unbatched) or 4D (batched) input to conv2d, but got input of size: [3, 16, 32, 32, 1]"
        );
        check("images".equals(result.highlightText()), "Conv2d 输入维数错误应标出输入");
        check(result.chineseExplanation().contains("[N,C,H,W]"), "应说明 Conv2d 标准输入结构");
    }

    private static void testStackSizeMismatch() throws IOException {
        String source = """
                def run(loader):
                    return next(iter(loader))
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "stack expects each tensor to be equal size, but got [3, 224, 224] at entry 0 and [3, 200, 224] at entry 1"
        );
        check(result.chineseExplanation().contains("[3, 224, 224]")
                        && result.chineseExplanation().contains("[3, 200, 224]"),
                "批处理堆叠错误应列出两种样本形状");
    }

    private static void testBatchNormSingleSample() throws IOException {
        String source = """
                def run(self, x):
                    return self.norm(x)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "ValueError",
                "Expected more than 1 value per channel when training, got input size torch.Size([1, 64])"
        );
        check("x".equals(result.highlightText()), "BatchNorm 单样本错误应标出输入");
        check(result.suggestions().stream().anyMatch(text -> text.contains("drop_last=True")),
                "应给出最后一个不足批次的处理方向");
    }

    private static void testTensorSizeMismatchExplainsDimension() throws IOException {
        String source = """
                def run(left, right):
                    return left + right
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "The size of tensor a (3) must match the size of tensor b (4) at non-singleton dimension 1"
        );
        check("left + right".equals(result.highlightText()), "张量尺寸不一致应标出参与运算的表达式");
        check(result.chineseExplanation().contains("第 1 维"), "应明确指出不匹配的维度");
        check(!result.tracedToRootCause(), "没有上游证据时只能标记触发行");
    }

    private static void testDeviceMismatchExplainsDevices() throws IOException {
        String source = """
                def run(self, x):
                    return self.fc(x)
                """;
        ErrorAnalysis result = analyze(
                source, 2, "RuntimeError",
                "Expected all tensors to be on the same device, but found at least two devices, cuda:0 and cpu!"
        );
        check("x".equals(result.highlightText()), "设备不一致应优先标出送入模型的输入");
        check(result.chineseExplanation().contains("cuda:0") && result.chineseExplanation().contains("cpu"),
                "应列出冲突设备");
    }

    private static void testMultilineLinearKeywordConfiguration() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.out = nn.Linear(
                            in_features=300,
                            out_features=10,
                        )
                    def forward(self, x):
                        return self.out(x)
                """;
        ErrorAnalysis result = analyze(
                source, 8, "RuntimeError",
                "mat1 and mat2 shapes cannot be multiplied (32x256 and 300x10)"
        );
        check(result.lineNumber() == 4, "多行 Linear 应定位到 in_features 所在行");
        check("300".equals(result.highlightText()), "多行参数应只标出错误数值");
    }

    private static void testMultilineLinearPositionalConfiguration() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.out = nn.Linear(
                            300,
                            10,
                        )
                    def forward(self, x):
                        return self.out(x)
                """;
        ErrorAnalysis result = analyze(
                source, 8, "RuntimeError",
                "mat1 and mat2 shapes cannot be multiplied (32x256 and 300x10)"
        );
        check(result.lineNumber() == 4, "多行位置参数 Linear 应定位到第一个参数");
        check("300".equals(result.highlightText()), "位置参数形式也应标出错误数值");
    }

    private static ErrorAnalysis analyze(String source,
                                         int failingLine,
                                         String exceptionType,
                                         String message) throws IOException {
        Path file = Files.createTempFile("python-error-corpus-", ".py");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            String sourceLine = Files.readAllLines(file, StandardCharsets.UTF_8).get(failingLine - 1).strip();
            String traceback = "Traceback (most recent call last):\n"
                    + "  File \"" + file + "\", line " + failingLine + ", in run\n"
                    + "    " + sourceLine + "\n"
                    + exceptionType + ": " + message + "\n";
            return PythonErrorAnalyzer.analyze(traceback, file.getParent().toString(), "corpus");
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
