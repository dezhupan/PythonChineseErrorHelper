package com.codex.pyerrorhelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class StateDictErrorCorpusTest {
    private StateDictErrorCorpusTest() {
    }

    static void runAll() throws Exception {
        testUserLinearInputMismatchTraceback();
        testMultipleSizeMismatchesAreCounted();
        testLinearOutputMismatchFindsOutFeatures();
        testLinearBiasMismatchFindsOutFeatures();
        testConvolutionCheckpointMismatch();
        testEmbeddingCheckpointMismatch();
        testMissingAndUnexpectedKeys();
        testModulePrefixAdvice();
        testSequentialIndexedLayerMismatch();
        testUnknownLayerKeepsLoadLocation();
        testMultilineMessageStopsBeforeProcessFooter();
    }

    private static void testUserLinearInputMismatchTraceback() throws IOException {
        String source = """
                class ImageModel(nn.Module):
                    def __init__(self):
                        super().__init__()
                        self.out = nn.Linear(in_features=65, out_features=10)

                def eval_model():
                    model = ImageModel()
                    model.load_state_dict(torch.load('./model/image_model.pth'))

                eval_model()
                """;
        String detail = "size mismatch for out.weight: copying a param with shape torch.Size([10, 84]) "
                + "from checkpoint, the shape in current model is torch.Size([10, 65]).";
        ErrorAnalysis result = analyzeStateDict(source, 8, "\t" + detail);
        check(result.originalMessage().contains(detail), "必须保留 RuntimeError 后续缩进的尺寸详情");
        check(result.analysisScope().contains("当前报错文件完整扫描"), "应明确只完整扫描当前报错文件");
        check(result.lineNumber() == 4, "应扫描整个运行文件并定位 self.out 定义");
        check("65".equals(result.highlightText()), "应标出当前模型不匹配的 in_features");
        check(result.replacementText().isBlank(), "无法判断保留哪套结构时不得自动把 65 改为 84");
        check(result.tracedToRootCause(), "从 load_state_dict 回溯到层定义后应标记为根因位置");
        check(result.chineseExplanation().contains("[10, 84]")
                        && result.chineseExplanation().contains("[10, 65]"),
                "中文解释必须直接对比检查点和当前模型形状");
    }

    private static void testMultipleSizeMismatchesAreCounted() throws IOException {
        String source = """
                class ImageModel(nn.Module):
                    def __init__(self):
                        self.out = nn.Linear(in_features=65, out_features=10)
                def eval_model():
                    model.load_state_dict(torch.load('old.pth'))
                """;
        String details = "\tsize mismatch for out.weight: copying a param with shape torch.Size([10, 84]) "
                + "from checkpoint, the shape in current model is torch.Size([10, 65]).\n"
                + "\tsize mismatch for out.bias: copying a param with shape torch.Size([12]) "
                + "from checkpoint, the shape in current model is torch.Size([10]).";
        ErrorAnalysis result = analyzeStateDict(source, 5, details);
        check(result.chineseExplanation().contains("共有 2 个参数形状不一致"), "多处不匹配应显示总数");
    }

    private static void testLinearOutputMismatchFindsOutFeatures() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.head = nn.Linear(in_features=84, out_features=10)
                def run():
                    model.load_state_dict(torch.load('old.pth'))
                """;
        String detail = "\tsize mismatch for head.weight: copying a param with shape torch.Size([12, 84]) "
                + "from checkpoint, the shape in current model is torch.Size([10, 84]).";
        ErrorAnalysis result = analyzeStateDict(source, 5, detail);
        check(result.lineNumber() == 3, "Linear 输出维度不一致应定位 out_features");
        check("10".equals(result.highlightText()), "应标出当前 out_features");
        check(result.highlightColumn() == source.lines().toList().get(2).indexOf("out_features=10")
                        + "out_features=".length(),
                "同一行有多个数字时应标到 out_features 的数值");
    }

    private static void testLinearBiasMismatchFindsOutFeatures() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.head = nn.Linear(in_features=84, out_features=10)
                def run():
                    model.load_state_dict(torch.load('old.pth'))
                """;
        String detail = "\tsize mismatch for head.bias: copying a param with shape torch.Size([12]) "
                + "from checkpoint, the shape in current model is torch.Size([10]).";
        ErrorAnalysis result = analyzeStateDict(source, 5, detail);
        check("10".equals(result.highlightText()), "Linear bias 不一致也应映射到 out_features");
    }

    private static void testConvolutionCheckpointMismatch() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.conv = nn.Conv2d(in_channels=1, out_channels=16, kernel_size=3)
                def run():
                    model.load_state_dict(torch.load('old.pth'))
                """;
        String detail = "\tsize mismatch for conv.weight: copying a param with shape torch.Size([16, 3, 3, 3]) "
                + "from checkpoint, the shape in current model is torch.Size([16, 1, 3, 3]).";
        ErrorAnalysis result = analyzeStateDict(source, 5, detail);
        check("1".equals(result.highlightText()), "卷积权重第二维不一致应映射到 in_channels");
    }

    private static void testEmbeddingCheckpointMismatch() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.embedding = nn.Embedding(num_embeddings=1000, embedding_dim=128)
                def run():
                    model.load_state_dict(torch.load('old.pth'))
                """;
        String detail = "\tsize mismatch for embedding.weight: copying a param with shape torch.Size([1200, 128]) "
                + "from checkpoint, the shape in current model is torch.Size([1000, 128]).";
        ErrorAnalysis result = analyzeStateDict(source, 5, detail);
        check("1000".equals(result.highlightText()), "Embedding 权重第一维不一致应映射到 num_embeddings");
    }

    private static void testMissingAndUnexpectedKeys() throws IOException {
        String source = """
                def run(model):
                    model.load_state_dict(torch.load('other.pth'))
                """;
        String details = "\tMissing key(s) in state_dict: \"head.weight\", \"head.bias\".\n"
                + "\tUnexpected key(s) in state_dict: \"classifier.weight\".";
        ErrorAnalysis result = analyzeStateDict(source, 2, details);
        check(result.originalMessage().contains("Missing key(s)")
                        && result.originalMessage().contains("Unexpected key(s)"),
                "必须保留全部缺失键和意外键详情");
        check(result.chineseExplanation().contains("同时存在缺失和多余"), "应识别两类键冲突同时发生");
        check("other.pth".equals(result.highlightText()), "未找到层定义时应标出正在加载的检查点");
    }

    private static void testModulePrefixAdvice() throws IOException {
        String source = """
                def run(model):
                    model.load_state_dict(torch.load('parallel.pth'))
                """;
        ErrorAnalysis result = analyzeStateDict(
                source,
                2,
                "\tUnexpected key(s) in state_dict: \"module.conv.weight\", \"module.conv.bias\"."
        );
        check(result.suggestions().stream().anyMatch(text -> text.contains("DataParallel")),
                "module. 前缀冲突应提示 DataParallel 来源");
    }

    private static void testSequentialIndexedLayerMismatch() throws IOException {
        String source = """
                class Model(nn.Module):
                    def __init__(self):
                        self.classifier = nn.Sequential(
                            nn.Linear(128, 64),
                            nn.ReLU(),
                            nn.Linear(in_features=65, out_features=10),
                        )
                def run():
                    model.load_state_dict(torch.load('old.pth'))
                """;
        String detail = "\tsize mismatch for classifier.2.weight: copying a param with shape torch.Size([10, 64]) "
                + "from checkpoint, the shape in current model is torch.Size([10, 65]).";
        ErrorAnalysis result = analyzeStateDict(source, 9, detail);
        check(result.lineNumber() == 6, "Sequential 数字索引参数应映射到对应子层定义");
        check("65".equals(result.highlightText()), "应标出索引子层的 in_features");
    }

    private static void testUnknownLayerKeepsLoadLocation() throws IOException {
        String source = """
                def run(model):
                    model.load_state_dict(torch.load('unknown.pth'))
                """;
        String detail = "\tsize mismatch for backbone.block.weight: copying a param with shape torch.Size([10, 84]) "
                + "from checkpoint, the shape in current model is torch.Size([10, 65]).";
        ErrorAnalysis result = analyzeStateDict(source, 2, detail);
        check(result.lineNumber() == 2, "无法可靠映射嵌套层时应保留加载位置");
        check(!result.tracedToRootCause(), "无法映射层定义时不能宣称已找到根因代码行");
        check("unknown.pth".equals(result.highlightText()), "应标出需要核对的模型文件");
    }

    private static void testMultilineMessageStopsBeforeProcessFooter() throws IOException {
        String source = """
                def run(model):
                    model.load_state_dict(torch.load('old.pth'))
                """;
        ErrorAnalysis result = analyzeStateDict(
                source,
                2,
                "\tMissing key(s) in state_dict: \"head.weight\"."
        );
        check(!result.originalMessage().contains("进程已结束"), "多行异常解析不能吞入 PyCharm 进程结束文字");
    }

    private static ErrorAnalysis analyzeStateDict(String source,
                                                  int failingLine,
                                                  String indentedDetails) throws IOException {
        Path file = Files.createTempFile("state-dict-error-", ".py");
        try {
            Files.writeString(file, source, StandardCharsets.UTF_8);
            String sourceLine = Files.readAllLines(file, StandardCharsets.UTF_8).get(failingLine - 1).strip();
            String traceback = "Traceback (most recent call last):\n"
                    + "  File \"" + file + "\", line " + failingLine + ", in run\n"
                    + "    " + sourceLine + "\n"
                    + "  File \"D:\\demo\\.venv\\Lib\\site-packages\\torch\\nn\\modules\\module.py\", line 2638, in load_state_dict\n"
                    + "    raise RuntimeError(...)\n"
                    + "RuntimeError: Error(s) in loading state_dict for Model:\n"
                    + indentedDetails + "\n\n"
                    + "进程已结束，退出代码为 1\n";
            return PythonErrorAnalyzer.analyze(traceback, file.getParent().toString(), "state-dict");
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
