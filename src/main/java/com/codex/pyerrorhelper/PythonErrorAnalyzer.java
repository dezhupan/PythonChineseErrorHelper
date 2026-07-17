package com.codex.pyerrorhelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PythonErrorAnalyzer {
    private static final Pattern ANSI_PATTERN = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");
    private static final Pattern FRAME_PATTERN = Pattern.compile(
            "(?m)^\\s*File [\\\"'](.+?)[\\\"'], line (\\d+)(?:, in ([^\\r\\n]+))?\\s*$");
    private static final Pattern EXCEPTION_PATTERN = Pattern.compile(
            "(?m)^([A-Za-z_][\\w.]*(?:Error|Exception|Warning|Interrupt))(?::\\s*(.*))?$");

    private PythonErrorAnalyzer() {
    }

    public static boolean looksLikePythonError(String output) {
        if (output == null || output.isBlank()) {
            return false;
        }
        String clean = stripAnsi(output);
        return clean.contains("Traceback (most recent call last):")
                || (FRAME_PATTERN.matcher(clean).find() && EXCEPTION_PATTERN.matcher(clean).find());
    }

    public static ErrorAnalysis analyze(String output, String projectBasePath, String runName) {
        String clean = stripAnsi(output).replace("\r\n", "\n").replace('\r', '\n').trim();
        List<Frame> frames = parseFrames(clean, projectBasePath);
        Frame selected = selectBestFrame(frames, projectBasePath);
        SourceFileScan sourceFileScan = scanSourceFile(selected);

        ExceptionDetails exception = parseExceptionDetails(clean);
        String exceptionType = exception.type();
        String originalMessage = exception.message();

        Advice advice = adviceFor(exceptionType, originalMessage, selected);
        Frame recommendedFrame = advice.locationOverride() == null ? selected : advice.locationOverride();
        String sourceLine = recommendedFrame == null ? "" : readSourceLine(recommendedFrame.path(), recommendedFrame.line());
        if (sourceLine.isBlank() && recommendedFrame == selected && selected != null) {
            sourceLine = sourceLineFollowingFrame(clean, selected);
        }
        String preciseHighlight = advice.highlightText();
        if (preciseHighlight.isBlank() && selected != null) {
            preciseHighlight = caretTargetFollowingFrame(clean, selected);
        }
        int highlightColumn = locateHighlightColumn(sourceLine, preciseHighlight, advice.highlightAnchor());
        boolean tracedToRootCause = advice.locationOverride() != null
                && (selected == null
                || recommendedFrame.line() != selected.line()
                || !recommendedFrame.path().equals(selected.path()));
        return new ErrorAnalysis(
                runName == null || runName.isBlank() ? "Python程序" : runName,
                sourceFileScan.description(),
                exceptionType,
                originalMessage,
                advice.explanation(),
                advice.suggestions(),
                recommendedFrame == null ? "" : recommendedFrame.path(),
                recommendedFrame == null ? 0 : recommendedFrame.line(),
                recommendedFrame == null ? "" : recommendedFrame.function(),
                sourceLine.strip(),
                preciseHighlight,
                highlightColumn,
                advice.replacementText(),
                tracedToRootCause,
                tail(clean, 12000)
        );
    }

    private static SourceFileScan scanSourceFile(Frame selectedFrame) {
        if (selectedFrame == null || selectedFrame.path().isBlank()) {
            return new SourceFileScan(false, 0, "仅控制台运行信息（未识别到项目源文件）");
        }
        try {
            List<String> lines = Files.readAllLines(Paths.get(selectedFrame.path()), StandardCharsets.UTF_8);
            return new SourceFileScan(
                    true,
                    lines.size(),
                    "控制台运行信息 + 当前报错文件完整扫描（共 " + lines.size() + " 行）"
            );
        } catch (IOException | InvalidPathException ignored) {
            return new SourceFileScan(false, 0, "控制台运行信息（当前源文件无法读取）");
        }
    }

    private static ExceptionDetails parseExceptionDetails(String output) {
        String exceptionType = "Python运行错误";
        String firstLineMessage = "";
        int exceptionLineEnd = -1;
        Matcher exceptionMatcher = EXCEPTION_PATTERN.matcher(output);
        while (exceptionMatcher.find()) {
            exceptionType = exceptionMatcher.group(1);
            firstLineMessage = safe(exceptionMatcher.group(2));
            exceptionLineEnd = exceptionMatcher.end();
        }
        if (exceptionLineEnd < 0) {
            return new ExceptionDetails(exceptionType, firstLineMessage);
        }

        StringBuilder message = new StringBuilder(firstLineMessage);
        int cursor = exceptionLineEnd;
        while (cursor < output.length()) {
            if (output.charAt(cursor) == '\n') {
                cursor++;
            }
            if (cursor >= output.length()) {
                break;
            }
            int lineEnd = output.indexOf('\n', cursor);
            if (lineEnd < 0) {
                lineEnd = output.length();
            }
            String line = output.substring(cursor, lineEnd);
            if (line.isBlank() || !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            String detail = line.strip();
            if (!detail.isBlank()) {
                if (!message.isEmpty()) {
                    message.append('\n');
                }
                message.append(detail);
            }
            cursor = lineEnd;
        }
        return new ExceptionDetails(exceptionType, message.toString());
    }

    private static List<Frame> parseFrames(String output, String projectBasePath) {
        List<Frame> frames = new ArrayList<>();
        Matcher matcher = FRAME_PATTERN.matcher(output);
        while (matcher.find()) {
            String path = normalizePath(matcher.group(1), projectBasePath);
            int line;
            try {
                line = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                line = 0;
            }
            frames.add(new Frame(path, line, safe(matcher.group(3)), matcher.start(), matcher.end()));
        }
        return frames;
    }

    private static Frame selectBestFrame(List<Frame> frames, String projectBasePath) {
        if (frames.isEmpty()) {
            return null;
        }
        if (projectBasePath != null && !projectBasePath.isBlank()) {
            try {
                Path base = Paths.get(projectBasePath).toAbsolutePath().normalize();
                for (int i = frames.size() - 1; i >= 0; i--) {
                    Path candidate = Paths.get(frames.get(i).path()).toAbsolutePath().normalize();
                    if (candidate.startsWith(base) && !isDependencyPath(candidate.toString())) {
                        return frames.get(i);
                    }
                }
            } catch (InvalidPathException ignored) {
                // Fall back to the deepest traceback frame.
            }
        }
        return frames.get(frames.size() - 1);
    }

    private static boolean isDependencyPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT).replace('\\', '/');
        return lower.contains("/site-packages/") || lower.contains("/.venv/")
                || lower.contains("/venv/") || lower.contains("/lib/python");
    }

    private static String normalizePath(String value, String projectBasePath) {
        try {
            Path path = Paths.get(value);
            if (!path.isAbsolute() && projectBasePath != null && !projectBasePath.isBlank()) {
                path = Paths.get(projectBasePath).resolve(path);
            }
            return path.normalize().toString();
        } catch (InvalidPathException ignored) {
            return value;
        }
    }

    private static String readSourceLine(String filePath, int lineNumber) {
        if (filePath == null || filePath.isBlank() || lineNumber <= 0) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            if (lineNumber <= lines.size()) {
                return lines.get(lineNumber - 1);
            }
        } catch (IOException | InvalidPathException ignored) {
            // The traceback text can still provide the source line.
        }
        return "";
    }

    private static String sourceLineFollowingFrame(String output, Frame frame) {
        int nextLineStart = output.indexOf('\n', frame.end());
        if (nextLineStart < 0) {
            return "";
        }
        int nextLineEnd = output.indexOf('\n', nextLineStart + 1);
        String line = output.substring(nextLineStart + 1, nextLineEnd < 0 ? output.length() : nextLineEnd).strip();
        if (line.startsWith("File ") || line.startsWith("Traceback ") || line.contains("Error:")) {
            return "";
        }
        return line;
    }

    private static Advice adviceFor(String type, String message, Frame frame) {
        String location = frame == null ? "报错堆栈指出的位置" : "第 " + frame.line() + " 行";
        String lowerMessage = message.toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        String explanation;
        String highlightText = "";
        String replacementText = "";
        String highlightAnchor = "";
        Frame locationOverride = null;

        switch (type) {
            case "SyntaxError" -> {
                explanation = "Python 无法理解这行代码的语法，真正缺少的符号有时位于箭头前一行。";
                if (lowerMessage.contains("expected ':'")) {
                    suggestions.add("在 if、for、while、def、class、try 等语句末尾补上英文冒号 :。");
                    String source = frame == null ? "" : readSourceLine(frame.path(), frame.line()).stripTrailing();
                    if (!source.isBlank()) {
                        highlightText = source.substring(source.length() - 1);
                        replacementText = highlightText + ":";
                    }
                } else if (lowerMessage.contains("unterminated string") || lowerMessage.contains("eol while scanning string")) {
                    suggestions.add("检查字符串开头和结尾的单引号或双引号是否成对。");
                } else if (lowerMessage.contains("never closed") || lowerMessage.contains("unexpected eof")) {
                    suggestions.add("检查当前行及上一行的圆括号、方括号或大括号是否闭合。");
                } else {
                    suggestions.add("检查 " + location + " 及上一行的冒号、逗号、引号和括号。");
                }
                suggestions.add("确认使用的是英文标点，避免中文逗号、冒号或引号混入代码。");
            }
            case "IndentationError", "TabError" -> {
                explanation = "代码块的缩进不符合 Python 规则，或同一代码块混用了 Tab 和空格。";
                suggestions.add("重新对齐 " + location + "，让它与同一代码块中的相邻语句保持一致。");
                suggestions.add("统一使用 4 个空格缩进，并将 Tab 转换为空格。");
            }
            case "NameError" -> {
                String name = quotedValue(message);
                String suggestedName = didYouMean(message);
                explanation = name.isBlank() ? "代码使用了尚未定义的名称。" : "名称 “" + name + "” 在使用时还没有定义。";
                highlightText = name;
                if (!name.isBlank() && !suggestedName.isBlank()) {
                    replacementText = suggestedName;
                    explanation += " Python 已识别到当前作用域里最接近的名称是 “" + suggestedName + "”。";
                    suggestions.add("把 “" + name + "” 改为 “" + suggestedName + "”，并核对这是否是你原本要使用的名称。");
                } else {
                    suggestions.add("检查 " + location + " 的变量或函数名是否拼写正确，包括大小写。");
                    suggestions.add("如果名称拼写正确，请在使用前先赋值、定义或正确导入它。");
                }
            }
            case "UnboundLocalError" -> {
                String name = quotedValue(message);
                highlightText = name;
                explanation = name.isBlank()
                        ? "函数把该名称当作局部变量，但在赋值之前就读取了它。"
                        : "局部变量 “" + name + "” 在赋值之前就被读取了。";
                suggestions.add("在函数中首次读取该变量之前给它赋初始值。");
                suggestions.add("如果你确实要修改外层变量，按作用域使用 global 或 nonlocal，但应先确认这是期望的设计。");
            }
            case "ModuleNotFoundError" -> {
                String module = quotedValue(message);
                explanation = module.isBlank() ? "当前 Python 解释器找不到要导入的模块。" : "当前 Python 解释器中找不到模块 “" + module + "”。";
                highlightText = module;
                suggestions.add("检查 import 后的模块名是否拼写正确，以及项目内文件名是否一致。");
                suggestions.add("在 PyCharm 的“Python 解释器”页面确认软件包已安装到当前项目正在使用的解释器，而不是另一个环境。");
            }
            case "ImportError" -> {
                explanation = "模块存在，但无法从中导入指定名称，常见原因是名称错误、版本不匹配或循环导入。";
                suggestions.add("核对 import 语句中的名称，以及安装的软件包版本是否提供该名称。");
                suggestions.add("如果两个项目文件互相导入，请把公共代码移到第三个模块以解除循环依赖。");
            }
            case "TypeError" -> {
                explanation = "当前操作收到的数据类型或参数形式与代码要求不一致。";
                boolean addTypeDebugSuggestion = true;
                if (lowerMessage.contains("reshape") && lowerMessage.contains("tuple of ints") && lowerMessage.contains("tensor")) {
                    explanation = "reshape 的形状参数必须是整数，但代码把一个 Tensor 当成了维度大小。";
                    suggestions.add("如果代码类似 output.reshape(-1, output[-1])，请把 output[-1] 改为 output.shape[-1] 或 output.size(-1)。");
                    suggestions.add("output[-1] 表示最后一行数据（Tensor）；output.shape[-1] 才表示最后一维的长度（整数）。");
                    String[] replacement = reshapeReplacement(frame);
                    highlightText = replacement[0];
                    replacementText = replacement[1];
                    addTypeDebugSuggestion = false;
                } else if (lowerMessage.contains("'method' object is not iterable")
                        || lowerMessage.contains("method object is not iterable")) {
                    explanation = "优化器收到的是 parameters 方法本身，而不是调用该方法后得到的参数序列。";
                    String[] replacement = parametersCallReplacement(frame);
                    highlightText = replacement[0];
                    replacementText = replacement[1];
                    suggestions.add("在高亮的 parameters 后补上调用括号 ()，例如 model.parameters()。");
                    suggestions.add("不带括号表示方法对象；带括号才会执行方法并返回模型参数。");
                    addTypeDebugSuggestion = false;
                } else if (lowerMessage.contains("numpy") && lowerMessage.contains("cuda")
                        && lowerMessage.contains("cpu")) {
                    RuntimeRootCauseDiagnosis diagnosis = diagnoseCudaNumpyConversion(message, frame);
                    if (diagnosis != null) {
                        explanation = diagnosis.explanation();
                        highlightText = diagnosis.highlightText();
                        replacementText = diagnosis.replacementText();
                        suggestions.addAll(diagnosis.suggestions());
                        addTypeDebugSuggestion = false;
                    } else {
                        suggestions.add("先把张量移动到 CPU，再转换为 NumPy 数组。");
                    }
                } else if (lowerMessage.contains("indices must be integers")
                        || lowerMessage.contains("indices must be integers or slices")) {
                    RuntimeRootCauseDiagnosis diagnosis = diagnoseWrongIndexType(message, frame);
                    if (diagnosis != null) {
                        explanation = diagnosis.explanation();
                        highlightText = diagnosis.highlightText();
                        suggestions.addAll(diagnosis.suggestions());
                        addTypeDebugSuggestion = false;
                    } else {
                        suggestions.add("列表和字符串需要整数索引；如果想按名称取值，请确认对象应该是字典。");
                    }
                } else if ((lowerMessage.contains("not iterable") || lowerMessage.contains("cannot unpack non-iterable"))
                        && !lowerMessage.contains("method")) {
                    RuntimeRootCauseDiagnosis diagnosis = diagnoseNotIterable(message, frame);
                    if (diagnosis != null) {
                        explanation = diagnosis.explanation();
                        highlightText = diagnosis.highlightText();
                        highlightAnchor = diagnosis.highlightAnchor();
                        locationOverride = diagnosis.location();
                        suggestions.addAll(diagnosis.suggestions());
                        addTypeDebugSuggestion = false;
                    } else {
                        suggestions.add("循环或拆包需要可迭代对象，请检查实际值是否为 None、数字或单个对象。");
                    }
                } else if (lowerMessage.contains("unexpected keyword argument")
                        || lowerMessage.contains("multiple values for argument")) {
                    String argument = quotedValue(message);
                    highlightText = argument;
                    explanation = lowerMessage.contains("unexpected keyword")
                            ? "函数定义中没有名为 “" + argument + "” 的关键字参数。"
                            : "参数 “" + argument + "” 同时通过位置和关键字重复传入了。";
                    suggestions.add("核对函数定义中的参数名，并检查调用处是否拼写错误或重复传参。");
                    addTypeDebugSuggestion = false;
                } else if (lowerMessage.contains("required positional argument")) {
                    String argument = lastQuotedValue(message);
                    explanation = argument.isBlank()
                            ? "调用函数时缺少必需的位置参数。"
                            : "调用函数时缺少必需参数 “" + argument + "”。";
                    suggestions.add("在当前函数调用中补齐报错指出的参数，并确认参数顺序与函数定义一致。");
                    addTypeDebugSuggestion = false;
                } else if (lowerMessage.contains("not callable")) {
                    RuntimeRootCauseDiagnosis diagnosis = diagnoseShadowedCallable(message, frame);
                    if (diagnosis != null) {
                        explanation = diagnosis.explanation();
                        highlightText = diagnosis.highlightText();
                        replacementText = diagnosis.replacementText();
                        highlightAnchor = diagnosis.highlightAnchor();
                        locationOverride = diagnosis.location();
                        suggestions.addAll(diagnosis.suggestions());
                        addTypeDebugSuggestion = false;
                    } else {
                        suggestions.add("检查圆括号前的对象；它现在不是函数，可能被同名变量覆盖。");
                    }
                } else if (lowerMessage.contains("not subscriptable")) {
                    RuntimeRootCauseDiagnosis diagnosis = diagnoseNonSubscriptable(message, frame);
                    if (diagnosis != null) {
                        explanation = diagnosis.explanation();
                        highlightText = diagnosis.highlightText();
                        highlightAnchor = diagnosis.highlightAnchor();
                        locationOverride = diagnosis.location();
                        suggestions.addAll(diagnosis.suggestions());
                        addTypeDebugSuggestion = false;
                    } else {
                        suggestions.add("检查方括号前的对象；它不支持使用 [索引] 读取。");
                    }
                } else if (lowerMessage.contains("unsupported operand")) {
                    RuntimeRootCauseDiagnosis diagnosis = diagnoseUnsupportedOperand(message, frame);
                    if (diagnosis != null) {
                        explanation = diagnosis.explanation();
                        highlightText = diagnosis.highlightText();
                        replacementText = diagnosis.replacementText();
                        highlightAnchor = diagnosis.highlightAnchor();
                        locationOverride = diagnosis.location();
                        suggestions.addAll(diagnosis.suggestions());
                        addTypeDebugSuggestion = false;
                    } else {
                        suggestions.add("检查运算符两侧的数据类型，必要时用 int、float 或 str 显式转换。");
                    }
                } else {
                    suggestions.add("查看报错消息中列出的实际类型，在 " + location + " 调整参数或先做类型转换。");
                }
                if (addTypeDebugSuggestion) {
                    suggestions.add("在出错行前临时打印 type(变量) 和变量值，可确认实际传入的数据。");
                }
            }
            case "AttributeError" -> {
                RuntimeRootCauseDiagnosis diagnosis = diagnoseAttributeError(message, frame);
                if (diagnosis != null) {
                    explanation = diagnosis.explanation();
                    highlightText = diagnosis.highlightText();
                    replacementText = diagnosis.replacementText();
                    highlightAnchor = diagnosis.highlightAnchor();
                    locationOverride = diagnosis.location();
                    suggestions.addAll(diagnosis.suggestions());
                } else {
                    explanation = "对象上不存在你访问的属性或方法，可能是对象类型不对、名称写错或对象为 None。";
                    suggestions.add("检查点号前对象的实际类型和值，再核对点号后的属性或方法拼写。");
                    suggestions.add("如果对象可能是 None，请追查它的赋值来源，而不是直接忽略异常。");
                }
            }
            case "ValueError" -> {
                RuntimeRootCauseDiagnosis diagnosis = diagnoseValueError(message, frame);
                if (diagnosis != null) {
                    explanation = diagnosis.explanation();
                    highlightText = diagnosis.highlightText();
                    replacementText = diagnosis.replacementText();
                    highlightAnchor = diagnosis.highlightAnchor();
                    locationOverride = diagnosis.location();
                    suggestions.addAll(diagnosis.suggestions());
                } else {
                    explanation = "数据类型通常是对的，但具体内容不符合函数要求，例如无法转换的文本或数量不匹配。";
                    suggestions.add("根据原始错误消息检查 " + location + " 传入的具体值。");
                    suggestions.add("在转换或拆包前验证输入格式、范围和元素数量。");
                }
            }
            case "IndexError" -> {
                RuntimeRootCauseDiagnosis diagnosis = diagnoseIndexError(message, frame);
                if (diagnosis != null) {
                    explanation = diagnosis.explanation();
                    highlightText = diagnosis.highlightText();
                    suggestions.addAll(diagnosis.suggestions());
                } else {
                    explanation = "列表、元组或字符串的索引超出了现有元素范围。";
                    suggestions.add("检查 " + location + " 使用的索引以及 len(对象)，索引必须小于长度。");
                    suggestions.add("循环中优先直接遍历元素，或使用 range(len(对象))，避免多加 1。");
                }
            }
            case "KeyError" -> {
                explanation = "字典中不存在代码正在读取的键。";
                highlightText = quotedValue(message);
                suggestions.add("核对键名、大小写和数据来源；可先用 key in 字典 判断是否存在。");
                suggestions.add("只有当缺少该键属于正常情况时，才使用 字典.get(key, 默认值)。");
            }
            case "FileNotFoundError" -> {
                RuntimeRootCauseDiagnosis diagnosis = diagnoseFileNotFound(message, frame);
                if (diagnosis != null) {
                    explanation = diagnosis.explanation();
                    highlightText = diagnosis.highlightText();
                    highlightAnchor = diagnosis.highlightAnchor();
                    locationOverride = diagnosis.location();
                    suggestions.addAll(diagnosis.suggestions());
                } else {
                    explanation = "程序要打开的文件或目录在指定位置不存在。";
                    suggestions.add("检查路径拼写、当前工作目录以及相对路径实际指向的位置。");
                    suggestions.add("Windows 路径建议使用 pathlib.Path、原始字符串 r\"...\" 或正斜杠，避免反斜杠转义。");
                }
            }
            case "PermissionError" -> {
                explanation = "程序没有权限读取、写入或占用目标文件。";
                suggestions.add("确认文件没有被其他程序锁定，并检查目标目录是否允许当前用户写入。");
                suggestions.add("不要直接写系统目录；优先改到项目目录或用户有权限的目录。");
            }
            case "ZeroDivisionError" -> {
                RuntimeRootCauseDiagnosis diagnosis = diagnoseZeroDivision(frame);
                if (diagnosis != null) {
                    explanation = diagnosis.explanation();
                    highlightText = diagnosis.highlightText();
                    highlightAnchor = diagnosis.highlightAnchor();
                    locationOverride = diagnosis.location();
                    suggestions.addAll(diagnosis.suggestions());
                } else {
                    explanation = "除法或取余运算的除数为 0。";
                    suggestions.add("在 " + location + " 运算前检查除数；为 0 时应明确返回、跳过或给出业务提示。");
                    suggestions.add("继续追查除数为什么会变成 0，不要仅用 try/except 隐藏问题。");
                }
            }
            case "AssertionError" -> {
                explanation = "assert 后的条件为假，说明实际状态没有满足代码假设。";
                suggestions.add("检查断言中的实际值以及产生该值的上游代码。");
                suggestions.add("不要为了让程序继续运行而直接删除断言，先确认假设为何不成立。");
            }
            case "RecursionError" -> {
                explanation = "函数递归调用次数过多，通常是终止条件缺失或没有被正确触发。";
                suggestions.add("检查递归函数的终止条件，并确认每次递归都在接近终止条件。");
                suggestions.add("数据量较大时可考虑改成循环实现，不建议单纯提高递归上限。");
            }
            case "UnicodeDecodeError", "UnicodeEncodeError" -> {
                explanation = "程序使用的字符编码与文件或文本的真实编码不一致。";
                suggestions.add("读取或写入文件时明确指定正确的 encoding，例如 encoding=\"utf-8\"。");
                suggestions.add("先确认原文件编码，不要盲目使用 errors=\"ignore\" 丢弃字符。");
            }
            case "MemoryError" -> {
                explanation = "程序申请的内存超过当前环境可用容量。";
                suggestions.add("避免一次性读取全部大文件或创建巨大列表，改为分块、生成器或流式处理。");
                suggestions.add("检查循环是否不断累积对象，并及时释放不再使用的大变量。");
            }
            case "RuntimeError" -> {
                RuntimeRootCauseDiagnosis diagnosis = diagnoseRuntimeRootCause(message, frame);
                if (diagnosis != null) {
                    explanation = diagnosis.explanation();
                    highlightText = diagnosis.highlightText();
                    replacementText = diagnosis.replacementText();
                    highlightAnchor = diagnosis.highlightAnchor();
                    locationOverride = diagnosis.location();
                    suggestions.addAll(diagnosis.suggestions());
                } else {
                    explanation = "程序运行时检测到张量、形状或计算状态不满足当前操作要求。";
                    suggestions.add("先核对报错消息中的张量形状，再检查当前层的输入维度与上一层输出维度是否一致。");
                    suggestions.add("在出错操作前打印 tensor.shape，可以确认实际进入该层的数据尺寸。");
                }
            }
            default -> {
                explanation = "Python 在运行到这里时抛出了 “" + type + "”。原始消息通常包含触发异常的直接原因。";
                suggestions.add("先查看上面标出的项目文件和代码行，再结合原始报错消息核对输入值与程序状态。");
                suggestions.add("从 traceback 最后一层向上阅读；项目目录内最靠后的调用通常最接近需要修改的位置。");
            }
        }
        if (frame == null) {
            suggestions.add("本次输出没有完整的文件行号；请展开运行窗口并确认完整 traceback 没有被截断。");
        }
        return new Advice(explanation, List.copyOf(suggestions), highlightText, replacementText,
                highlightAnchor, locationOverride);
    }

    private static RuntimeRootCauseDiagnosis diagnoseRuntimeRootCause(String message, Frame tracebackFrame) {
        RuntimeRootCauseDiagnosis diagnosis = diagnoseStateDictLoadError(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseMatrixShapeMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseRnnInputMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseConvolutionChannelMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseBatchNormMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseLayerNormMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseNumpyRequiresDetach(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseInvalidTensorShape(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseTensorScalarConversion(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseConvolutionInputRank(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseStackSizeMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseTensorDtypeMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseExpectedScalarType(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        diagnosis = diagnoseTensorSizeMismatch(message, tracebackFrame);
        if (diagnosis != null) {
            return diagnosis;
        }
        return diagnoseDeviceMismatch(message, tracebackFrame);
    }

    private static RuntimeRootCauseDiagnosis diagnoseStateDictLoadError(String message, Frame tracebackFrame) {
        if (tracebackFrame == null
                || !message.toLowerCase(Locale.ROOT).contains("loading state_dict")) {
            return null;
        }

        List<StateDictSizeMismatch> sizeMismatches = new ArrayList<>();
        Matcher mismatchMatcher = Pattern.compile(
                "size mismatch for ([A-Za-z0-9_.]+):\\s*copying a param with shape torch\\.Size\\(\\[([^]]+)]\\)"
                        + " from checkpoint, the shape in current model is torch\\.Size\\(\\[([^]]+)]\\)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        while (mismatchMatcher.find()) {
            List<Integer> checkpointShape = parseIntegerShape(mismatchMatcher.group(2));
            List<Integer> currentShape = parseIntegerShape(mismatchMatcher.group(3));
            if (!checkpointShape.isEmpty() && !currentShape.isEmpty()) {
                sizeMismatches.add(new StateDictSizeMismatch(
                        mismatchMatcher.group(1), checkpointShape, currentShape));
            }
        }

        String checkpointTarget = checkpointPathTarget(tracebackFrame);
        if (!sizeMismatches.isEmpty()) {
            StateDictSizeMismatch first = sizeMismatches.get(0);
            LayerParameterLocation definition = findStateDictParameterDefinition(tracebackFrame, first);
            Frame location = definition == null ? tracebackFrame : definition.location();
            String highlight = definition == null ? checkpointTarget : definition.highlightText();
            String anchor = definition == null ? "" : definition.highlightAnchor();
            String countText = sizeMismatches.size() > 1
                    ? "，本次共有 " + sizeMismatches.size() + " 个参数形状不一致"
                    : "";
            return new RuntimeRootCauseDiagnosis(
                    location,
                    "正在加载的模型文件与当前源码定义的网络结构不一致。参数 “" + first.parameterName()
                            + "” 在检查点中的形状是 " + formatShape(first.checkpointShape())
                            + "，当前模型中是 " + formatShape(first.currentShape()) + countText + "。",
                    List.of(
                            "如果当前源码结构是你想要的，请加载与它匹配的模型文件，或者重新训练并保存新的 state_dict。",
                            "如果必须使用这个旧模型文件，请恢复保存它时的完整网络结构；不能只改 load_state_dict 这一行。",
                            "strict=False 不能自动忽略同名参数的尺寸冲突；如果是有意更换分类头，需要过滤不兼容参数并重新训练该层。"
                    ),
                    highlight,
                    "",
                    anchor
            );
        }

        boolean missingKeys = message.toLowerCase(Locale.ROOT).contains("missing key(s) in state_dict");
        boolean unexpectedKeys = message.toLowerCase(Locale.ROOT).contains("unexpected key(s) in state_dict");
        if (missingKeys || unexpectedKeys) {
            String reason;
            if (missingKeys && unexpectedKeys) {
                reason = "当前模型需要的参数名与检查点提供的参数名同时存在缺失和多余，通常说明加载了不同结构的模型文件。";
            } else if (missingKeys) {
                reason = "当前模型需要的一部分参数在检查点中不存在，可能是检查点较旧、网络新增了层或参数名称已经改变。";
            } else {
                reason = "检查点包含当前模型没有的参数，可能是加载了不同网络、删除了层，或保存时带有 module. 前缀。";
            }
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    reason,
                    List.of(
                            "确认训练和加载阶段使用的是同一个模型类、同一套层名称和同一版本代码。",
                            "如果键统一多出 module.，检查模型是否曾用 DataParallel 保存；应有目的地转换键名，而不是盲目 strict=False。",
                            "只有明确允许部分加载时才筛选键，并对未加载的层重新初始化和训练。"
                    ),
                    checkpointTarget,
                    "",
                    ""
            );
        }
        return null;
    }

    private static List<Integer> parseIntegerShape(String shapeText) {
        List<Integer> dimensions = new ArrayList<>();
        for (String part : shapeText.split(",")) {
            try {
                dimensions.add(Integer.parseInt(part.strip()));
            } catch (NumberFormatException ignored) {
                return List.of();
            }
        }
        return List.copyOf(dimensions);
    }

    private static String formatShape(List<Integer> shape) {
        return "[" + shape.stream().map(String::valueOf).reduce((left, right) -> left + ", " + right).orElse("") + "]";
    }

    private static String checkpointPathTarget(Frame tracebackFrame) {
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher path = Pattern.compile("['\"]([^'\"]+\\.(?:pth|pt|ckpt|bin))['\"]", Pattern.CASE_INSENSITIVE)
                .matcher(source);
        return path.find() ? path.group(1) : "";
    }

    private static LayerParameterLocation findStateDictParameterDefinition(Frame tracebackFrame,
                                                                            StateDictSizeMismatch mismatch) {
        String normalizedKey = mismatch.parameterName().startsWith("module.")
                ? mismatch.parameterName().substring("module.".length())
                : mismatch.parameterName();
        String[] keyParts = normalizedKey.split("\\.");
        if (keyParts.length < 2) {
            return null;
        }
        Integer sequentialIndex = null;
        String attribute = keyParts[keyParts.length - 2];
        if (attribute.chars().allMatch(Character::isDigit)) {
            if (keyParts.length < 3) {
                return null;
            }
            sequentialIndex = Integer.parseInt(attribute);
            attribute = keyParts[keyParts.length - 3];
        }
        int mismatchIndex = firstShapeMismatchIndex(mismatch.checkpointShape(), mismatch.currentShape());
        try {
            List<String> lines = Files.readAllLines(Paths.get(tracebackFrame.path()), StandardCharsets.UTF_8);
            Pattern definitionPattern = Pattern.compile(
                    "self\\." + Pattern.quote(attribute)
                            + "\\s*=\\s*nn\\.([A-Za-z_][\\w]*)");
            for (int i = 0; i < lines.size(); i++) {
                Matcher definition = definitionPattern.matcher(lines.get(i));
                if (!definition.find()) {
                    continue;
                }
                int end = layerStatementEnd(lines, i);
                String block = String.join("\n", lines.subList(i, end + 1));
                if (sequentialIndex != null
                        && (definition.group(1).equals("Sequential") || definition.group(1).equals("ModuleList"))) {
                    LayerParameterLocation nested = findIndexedContainerParameter(
                            tracebackFrame, block, i, sequentialIndex, normalizedKey, mismatch, mismatchIndex);
                    if (nested != null) {
                        return nested;
                    }
                }
                String parameter = stateDictConfigParameter(
                        definition.group(1), normalizedKey, mismatchIndex);
                if (!parameter.isBlank() && mismatchIndex >= 0 && mismatchIndex < mismatch.currentShape().size()) {
                    int currentValue = mismatch.currentShape().get(mismatchIndex);
                    if (declaresLayerParameter(block, "nn\\." + Pattern.quote(definition.group(1)),
                            parameter, currentValue)) {
                        int parameterLine = parameterLineInBlock(
                                block, i, "nn\\." + Pattern.quote(definition.group(1)), parameter, currentValue);
                        return new LayerParameterLocation(
                                new Frame(tracebackFrame.path(), parameterLine + 1, "__init__", 0, 0),
                                Integer.toString(currentValue),
                                parameter
                        );
                    }
                }
                return new LayerParameterLocation(
                        new Frame(tracebackFrame.path(), i + 1, "__init__", 0, 0),
                        "self." + attribute,
                        ""
                );
            }
        } catch (IOException | InvalidPathException ignored) {
            return null;
        }
        return null;
    }

    private static LayerParameterLocation findIndexedContainerParameter(Frame tracebackFrame,
                                                                         String containerBlock,
                                                                         int blockStartLine,
                                                                         int targetIndex,
                                                                         String parameterKey,
                                                                         StateDictSizeMismatch mismatch,
                                                                         int mismatchIndex) {
        Matcher modules = Pattern.compile("nn\\.([A-Za-z_][\\w]*)\\s*\\(").matcher(containerBlock);
        boolean skippedContainer = false;
        int childIndex = -1;
        while (modules.find()) {
            if (!skippedContainer) {
                skippedContainer = true;
                continue;
            }
            childIndex++;
            if (childIndex != targetIndex) {
                continue;
            }
            String childType = modules.group(1);
            int childStart = modules.start();
            int childEnd = matchingCallEnd(containerBlock, modules.end() - 1);
            if (childEnd <= childStart) {
                return null;
            }
            String childBlock = containerBlock.substring(childStart, childEnd + 1);
            String parameter = stateDictConfigParameter(childType, parameterKey, mismatchIndex);
            if (parameter.isBlank() || mismatchIndex < 0 || mismatchIndex >= mismatch.currentShape().size()) {
                return null;
            }
            int currentValue = mismatch.currentShape().get(mismatchIndex);
            String layerPattern = "nn\\." + Pattern.quote(childType);
            int childStartLine = blockStartLine + countNewlines(containerBlock, childStart);
            if (declaresLayerParameter(childBlock, layerPattern, parameter, currentValue)) {
                int parameterLine = parameterLineInBlock(
                        childBlock, childStartLine, layerPattern, parameter, currentValue);
                return new LayerParameterLocation(
                        new Frame(tracebackFrame.path(), parameterLine + 1, "__init__", 0, 0),
                        Integer.toString(currentValue),
                        parameter
                );
            }
            return new LayerParameterLocation(
                    new Frame(tracebackFrame.path(), childStartLine + 1, "__init__", 0, 0),
                    "nn." + childType,
                    ""
            );
        }
        return null;
    }

    private static int matchingCallEnd(String text, int openingParenthesis) {
        int balance = 0;
        for (int i = openingParenthesis; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character == '(') {
                balance++;
            } else if (character == ')') {
                balance--;
                if (balance == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int firstShapeMismatchIndex(List<Integer> checkpointShape, List<Integer> currentShape) {
        int length = Math.min(checkpointShape.size(), currentShape.size());
        for (int i = 0; i < length; i++) {
            if (!checkpointShape.get(i).equals(currentShape.get(i))) {
                return i;
            }
        }
        return checkpointShape.size() == currentShape.size() ? -1 : length;
    }

    private static String stateDictConfigParameter(String layerType, String parameterKey, int mismatchIndex) {
        String lowerLayer = layerType.toLowerCase(Locale.ROOT);
        boolean weight = parameterKey.endsWith(".weight");
        boolean bias = parameterKey.endsWith(".bias");
        if (lowerLayer.equals("linear")) {
            return weight && mismatchIndex == 1 ? "in_features" : "out_features";
        }
        if (lowerLayer.startsWith("conv")) {
            if (weight && mismatchIndex == 1) {
                return "in_channels";
            }
            if ((weight && mismatchIndex == 0) || bias) {
                return "out_channels";
            }
            return "kernel_size";
        }
        if (lowerLayer.equals("embedding")) {
            return mismatchIndex == 0 ? "num_embeddings" : "embedding_dim";
        }
        if (lowerLayer.startsWith("batchnorm")) {
            return "num_features";
        }
        if (lowerLayer.equals("layernorm")) {
            return "normalized_shape";
        }
        return "";
    }

    private static RuntimeRootCauseDiagnosis diagnoseUnsupportedOperand(String message, Frame tracebackFrame) {
        if (tracebackFrame == null || tracebackFrame.path().isBlank()) {
            return null;
        }
        Matcher error = Pattern.compile(
                "unsupported operand type\\(s\\) for\\s+([^:]+):\\s*'([^']+)'\\s+and\\s+'([^']+)'",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!error.find()) {
            return null;
        }
        String operator = error.group(1).strip();
        String leftType = error.group(2).toLowerCase(Locale.ROOT);
        String rightType = error.group(3).toLowerCase(Locale.ROOT);
        if (!(operator.equals("//") || operator.equals("/") || operator.equals("%"))) {
            return null;
        }

        String failingSource = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher operands = Pattern.compile(
                "([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*"
                        + Pattern.quote(operator)
                        + "\\s*([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)")
                .matcher(failingSource);
        if (!operands.find()) {
            return null;
        }

        String wrongOperand;
        String wrongType;
        if (isContainerType(leftType) && isNumericType(rightType)) {
            wrongOperand = operands.group(1);
            wrongType = leftType;
        } else if (isNumericType(leftType) && isContainerType(rightType)) {
            wrongOperand = operands.group(2);
            wrongType = rightType;
        } else {
            return null;
        }
        if (!isLengthLikeName(wrongOperand)) {
            return null;
        }

        Assignment assignment = findMostRecentAssignment(tracebackFrame, wrongOperand);
        if (assignment == null || !isSimpleReference(assignment.expression())
                || assignment.expression().startsWith("len(")) {
            return null;
        }
        String replacement = "len(" + assignment.expression() + ")";
        return new RuntimeRootCauseDiagnosis(
                assignment.location(),
                "报错行的 “" + operator + "” 只是错误爆发点。变量 " + wrongOperand
                        + " 在前面被直接赋成了 " + wrongType + "，但它的名称和后续用途表明这里需要保存元素数量。",
                List.of(
                        "把 “" + wrongOperand + " = " + assignment.expression() + "” 改为 “"
                                + wrongOperand + " = " + replacement + "”。",
                        assignment.expression() + " 是列表等容器，不能直接与整数进行 “" + operator
                                + "” 运算；len(...) 返回的元素数量才是整数。"
                ),
                assignment.expression(),
                replacement,
                wrongOperand
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseShadowedCallable(String message, Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        Matcher errorType = Pattern.compile("'([^']+)' object is not callable", Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (!errorType.find()) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher calls = Pattern.compile("\\b([A-Za-z_][\\w]*)\\s*\\(").matcher(source);
        while (calls.find()) {
            String callable = calls.group(1);
            if (!isKnownBuiltinCallable(callable)) {
                continue;
            }
            Assignment assignment = findMostRecentAssignment(tracebackFrame, callable);
            if (assignment == null || !expressionMatchesType(assignment.expression(), errorType.group(1))) {
                continue;
            }
            String renamed = callable + "_value";
            return new RuntimeRootCauseDiagnosis(
                    assignment.location(),
                    "内置函数 “" + callable + "” 在前面被同名变量覆盖成了 " + errorType.group(1)
                            + "，所以后面的 " + callable + "(...) 已经不能调用。",
                    List.of(
                            "把保存数据的变量 “" + callable + "” 重命名，例如改为 “" + renamed + "”。",
                            "不要使用 len、list、str、sum、max 等 Python 内置函数名作为普通变量名。"
                    ),
                    callable,
                    renamed,
                    ""
            );
        }
        return null;
    }

    private static RuntimeRootCauseDiagnosis diagnoseNonSubscriptable(String message, Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        Matcher errorType = Pattern.compile("'([^']+)' object is not subscriptable", Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (!errorType.find()) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher indexed = Pattern.compile("([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*\\[").matcher(source);
        if (!indexed.find()) {
            return null;
        }
        String variable = indexed.group(1);
        Assignment assignment = findMostRecentAssignment(tracebackFrame, variable);
        if (assignment == null || !expressionMatchesType(assignment.expression(), errorType.group(1))) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                assignment.location(),
                "变量 “" + variable + "” 在前面被赋成了 " + errorType.group(1)
                        + "，这种对象不能使用 [索引] 读取。",
                List.of(
                        "检查这次赋值是否遗漏了列表、字典或函数返回值，并确认 “" + variable + "” 应保存什么类型。",
                        "不要只删除索引；应修正产生错误类型的赋值来源。"
                ),
                assignment.expression(),
                "",
                variable
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseCudaNumpyConversion(String message, Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher numpyCall = Pattern.compile("([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\.numpy\\(\\)")
                .matcher(source);
        if (!numpyCall.find()) {
            return null;
        }
        String receiver = numpyCall.group(1);
        String original = receiver + ".numpy()";
        String replacement = receiver + ".detach().cpu().numpy()";
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "NumPy 只能读取 CPU 内存，但当前张量位于 CUDA 设备。",
                List.of(
                        "先 detach，再移动到 CPU，最后调用 numpy()。",
                        "如果后续仍要反向传播，不要在训练计算链中间转成 NumPy。"
                ),
                original,
                replacement,
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseWrongIndexType(String message, Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher index = Pattern.compile("\\[([^]\\r\\n]+)]").matcher(source);
        if (!index.find()) {
            return null;
        }
        String target = index.group(1).strip();
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "当前对象使用了不支持的索引类型 “" + target + "”。列表和字符串只能使用整数或切片。",
                List.of(
                        "如果对象确实是列表，请把索引改为整数位置。",
                        "如果你想按键名读取，请检查上游是否本应创建字典而不是列表。"
                ),
                target,
                "",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseNotIterable(String message, Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        Matcher typeMatcher = Pattern.compile(
                "(?:'([^']+)' object is not iterable|cannot unpack non-iterable ([A-Za-z_][\\w]*) object)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!typeMatcher.find()) {
            return null;
        }
        String actualType = typeMatcher.group(1) != null ? typeMatcher.group(1) : typeMatcher.group(2);
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher forTarget = Pattern.compile("\\bfor\\s+[A-Za-z_][\\w]*\\s+in\\s+([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)")
                .matcher(source);
        String variable = forTarget.find() ? forTarget.group(1) : "";
        if (variable.isBlank()) {
            int equals = source.indexOf('=');
            if (equals > 0) {
                String right = source.substring(equals + 1).strip();
                if (isSimpleReference(right)) {
                    variable = right;
                }
            }
        }
        if (variable.isBlank()) {
            return null;
        }
        Assignment assignment = findMostRecentAssignment(tracebackFrame, variable);
        if (assignment == null) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "变量 “" + variable + "” 的实际类型是 " + actualType + "，不能用于循环或拆包。",
                    List.of("检查函数返回值和空结果处理，确认这里应得到列表、元组或其他可迭代对象。"),
                    variable,
                    "",
                    ""
            );
        }
        return new RuntimeRootCauseDiagnosis(
                assignment.location(),
                "变量 “" + variable + "” 在这里得到不可迭代的 " + actualType + "，后面的循环或拆包只是错误爆发点。",
                List.of(
                        "检查 “" + assignment.expression() + "” 是否遗漏 return，或在没有结果时返回了 None。",
                        "需要空集合时应明确返回 [] 或 ()，不要让正常分支和失败分支返回完全不同的类型。"
                ),
                assignment.expression(),
                "",
                variable
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseAttributeError(String message, Frame tracebackFrame) {
        Matcher error = Pattern.compile("'([^']+)' object has no attribute '([^']+)'", Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (!error.find()) {
            return null;
        }
        String objectType = error.group(1);
        String attribute = error.group(2);
        String suggested = didYouMean(message);
        if (!suggested.isBlank()) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "对象上没有属性 “" + attribute + "”，Python 找到的最接近属性是 “" + suggested + "”。",
                    List.of("把属性名 “" + attribute + "” 改为 “" + suggested + "”，并核对对象类型是否正确。"),
                    attribute,
                    suggested,
                    ""
            );
        }
        if (!objectType.equalsIgnoreCase("NoneType") || tracebackFrame == null) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "当前对象类型是 " + objectType + "，它没有属性或方法 “" + attribute + "”。",
                    List.of(
                            "检查点号前对象的实际类型，再核对属性名拼写。",
                            "如果对象类型不符合预期，请继续检查它最近的赋值或函数返回值。"
                    ),
                    attribute,
                    "",
                    ""
            );
        }

        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher baseMatcher = Pattern.compile(
                "([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\s*\\.\\s*" + Pattern.quote(attribute) + "\\b")
                .matcher(source);
        if (!baseMatcher.find()) {
            return null;
        }
        String variable = baseMatcher.group(1);
        Assignment assignment = findMostRecentAssignment(tracebackFrame, variable);
        if (assignment == null) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "变量 “" + variable + "” 当前是 None，因此不能访问 “" + attribute + "”。",
                    List.of("检查 “" + variable + "” 的产生位置，并在访问属性前处理未找到结果的情况。"),
                    variable,
                    "",
                    ""
            );
        }
        return new RuntimeRootCauseDiagnosis(
                assignment.location(),
                "变量 “" + variable + "” 在这里得到 None，后面的属性访问只是错误爆发点。",
                List.of(
                        "检查 “" + assignment.expression() + "” 为什么返回 None，并处理查询失败或函数缺少 return 的情况。",
                        "不要用 try/except 直接忽略 AttributeError；应保证返回有效对象或显式判断 None。"
                ),
                assignment.expression(),
                "",
                variable
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseValueError(String message, Frame tracebackFrame) {
        Matcher batchNormBatch = Pattern.compile(
                "Expected more than 1 value per channel when training, got input size (.+)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (batchNormBatch.find()) {
            List<String> arguments = simpleCallArguments(tracebackFrame);
            String input = arguments.isEmpty() ? "" : arguments.get(0);
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "BatchNorm 在训练模式下收到的有效样本数只有 1，无法计算稳定的批统计量。输入大小为 "
                            + batchNormBatch.group(1) + "。",
                    List.of(
                            "检查 batch_size 和最后一个不足批次；训练时可按业务需要设置 DataLoader(drop_last=True)。",
                            "验证或推理阶段应调用 model.eval()，但不要在正常训练中用 eval() 掩盖批大小问题。"
                    ),
                    input,
                    "",
                    ""
            );
        }
        Matcher batch = Pattern.compile(
                "Expected input batch_size \\((\\d+)\\) to match target batch_size \\((\\d+)\\)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (batch.find()) {
            List<String> arguments = simpleCallArguments(tracebackFrame);
            String target = arguments.size() >= 2 ? arguments.get(1) : "";
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "模型输出的批大小是 " + batch.group(1) + "，但标签批大小是 " + batch.group(2) + "，两者无法逐样本计算损失。",
                    List.of(
                            "检查 DataLoader、最后一个批次和 reshape/view 操作，保证输出与标签的第 0 维一致。",
                            "不要为了消除报错直接截断数据，先查明是哪一步改变了样本数量。"
                    ),
                    target,
                    "",
                    ""
            );
        }
        Matcher targetSize = Pattern.compile(
                "target size .* different to the input size", Pattern.CASE_INSENSITIVE).matcher(message);
        if (targetSize.find()) {
            List<String> arguments = simpleCallArguments(tracebackFrame);
            String target = arguments.size() >= 2 ? arguments.get(1) : "";
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "损失函数收到的预测张量与目标张量形状不同。",
                    List.of(
                            "打印预测和标签的 shape，确认批大小、类别维和额外的单例维是否一致。",
                            "根据所用损失函数决定是否需要 squeeze/unsqueeze，而不是盲目 reshape。"
                    ),
                    target,
                    "",
                    ""
            );
        }
        Matcher unpack = Pattern.compile("(not enough|too many) values to unpack", Pattern.CASE_INSENSITIVE)
                .matcher(message);
        if (unpack.find() && tracebackFrame != null) {
            String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
            int equals = source.indexOf('=');
            String left = equals > 0 ? source.substring(0, equals).strip() : "";
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "赋值左侧的变量数量与右侧实际返回的元素数量不一致。",
                    List.of(
                            "核对右侧函数或序列实际返回几个值，再调整左侧变量数量。",
                            "如果只需要部分值，可以使用下划线接收忽略项，或使用 *rest 收集剩余项。"
                    ),
                    left,
                    "",
                    ""
            );
        }
        Matcher invalidLiteral = Pattern.compile("invalid literal for int\\(\\) with base \\d+: ['\"]([^'\"]+)['\"]")
                .matcher(message);
        if (invalidLiteral.find()) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "文本 “" + invalidLiteral.group(1) + "” 不是有效整数，int(...) 无法转换。",
                    List.of("在转换前校验输入内容，或根据真实格式改用 float(...)、清理空白和非数字字符。"),
                    invalidLiteral.group(1),
                    "",
                    ""
            );
        }
        return null;
    }

    private static RuntimeRootCauseDiagnosis diagnoseIndexError(String message, Frame tracebackFrame) {
        Matcher target = Pattern.compile("Target ([-+]?\\d+) is out of bounds", Pattern.CASE_INSENSITIVE).matcher(message);
        if (target.find()) {
            List<String> arguments = simpleCallArguments(tracebackFrame);
            String labels = arguments.size() >= 2 ? arguments.get(1) : "";
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "分类标签中出现了越界类别 " + target.group(1) + "；CrossEntropyLoss 要求标签位于 0 到 类别数-1。",
                    List.of(
                            "检查标签编码是否从 0 开始，以及模型最后一层输出的类别数是否覆盖全部标签。",
                            "打印 target.min()、target.max() 和 output.shape[1] 进行核对。"
                    ),
                    labels,
                    "",
                    ""
            );
        }
        if (message.toLowerCase(Locale.ROOT).contains("index out of range in self") && tracebackFrame != null) {
            String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
            if (source.toLowerCase(Locale.ROOT).contains("embedding")) {
                List<String> arguments = simpleCallArguments(tracebackFrame);
                String indices = arguments.isEmpty() ? "" : arguments.get(0);
                return new RuntimeRootCauseDiagnosis(
                        tracebackFrame,
                        "Embedding 收到了超出词表范围的索引，所有索引必须满足 0 <= index < num_embeddings。",
                        List.of(
                                "打印输入索引的最小值和最大值，并与 Embedding 的 num_embeddings 对比。",
                                "检查词表编号、未知词编号和数据预处理是否使用了同一套映射。"
                        ),
                        indices,
                        "",
                        ""
                );
            }
        }
        if (tracebackFrame != null) {
            String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
            Matcher index = Pattern.compile("\\[([^]\\r\\n]+)]").matcher(source);
            if (index.find()) {
                return new RuntimeRootCauseDiagnosis(
                        tracebackFrame,
                        "索引表达式超出了序列现有范围。",
                        List.of("同时检查高亮索引的值和被索引对象的 len(...)，注意最大有效索引是长度减 1。"),
                        index.group(1).strip(),
                        "",
                        ""
                );
            }
        }
        return null;
    }

    private static RuntimeRootCauseDiagnosis diagnoseFileNotFound(String message, Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher call = Pattern.compile(
                "(?:open|read_csv|read_excel|read_json|load|imread)\\s*\\(\\s*([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)")
                .matcher(source);
        if (!call.find()) {
            return null;
        }
        String variable = call.group(1);
        Assignment assignment = findMostRecentAssignment(tracebackFrame, variable);
        if (assignment == null || !isStringLiteral(assignment.expression())) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                assignment.location(),
                "文件读取在后面失败，但实际路径是在这里配置的。",
                List.of(
                        "核对该路径是否存在、文件名大小写和扩展名是否正确。",
                        "相对路径还要结合运行配置中的工作目录判断；可使用 pathlib.Path(...).resolve() 查看最终位置。"
                ),
                assignment.expression(),
                "",
                variable
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseZeroDivision(Frame tracebackFrame) {
        if (tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher division = Pattern.compile(
                "(?:/{1,2}|%)\\s*([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*|[-+]?\\d+(?:\\.\\d+)?)")
                .matcher(source);
        if (!division.find()) {
            return null;
        }
        String divisor = division.group(1);
        if (isZeroLiteral(divisor)) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "除数在当前表达式中直接写成了 0。",
                    List.of("把除数改为业务上正确的非零值，或在运算前明确处理除数为 0 的情况。"),
                    divisor,
                    "",
                    ""
            );
        }
        Assignment assignment = findMostRecentAssignment(tracebackFrame, divisor);
        if (assignment == null || !isZeroLiteral(assignment.expression())) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                assignment.location(),
                "变量 “" + divisor + "” 在这里被赋为 0，后面的除法只是错误爆发点。",
                List.of(
                        "检查这个 0 是错误初始值还是合法输入；合法时应在除法前单独处理。",
                        "不要只捕获 ZeroDivisionError，应该修正除数来源或定义清晰的零值行为。"
                ),
                assignment.expression(),
                "",
                divisor
        );
    }

    private static Assignment findMostRecentAssignment(Frame tracebackFrame, String variable) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(tracebackFrame.path()), StandardCharsets.UTF_8);
            Pattern assignmentPattern = Pattern.compile(
                    "^\\s*" + Pattern.quote(variable) + "\\s*=\\s*(.+?)\\s*(?:#.*)?$");
            for (int i = Math.min(tracebackFrame.line() - 2, lines.size() - 1); i >= 0; i--) {
                String stripped = lines.get(i).stripLeading();
                if (stripped.startsWith("def ") || stripped.startsWith("async def ")) {
                    break;
                }
                Matcher assignment = assignmentPattern.matcher(lines.get(i));
                if (assignment.matches()) {
                    String expression = assignment.group(1).strip();
                    if (!expression.isBlank()) {
                        return new Assignment(
                                new Frame(tracebackFrame.path(), i + 1, tracebackFrame.function(), 0, 0),
                                expression
                        );
                    }
                }
            }
        } catch (IOException | InvalidPathException ignored) {
            // Keep the original traceback location when source history cannot be read reliably.
        }
        return null;
    }

    private static List<String> simpleCallArguments(Frame frame) {
        if (frame == null) {
            return List.of();
        }
        String source = readSourceLine(frame.path(), frame.line());
        Matcher calls = Pattern.compile("[A-Za-z_][\\w.]*\\s*\\(([^()]*)\\)").matcher(source);
        List<String> best = List.of();
        while (calls.find()) {
            String content = calls.group(1).strip();
            if (content.isBlank()) {
                continue;
            }
            List<String> arguments = new ArrayList<>();
            for (String part : content.split(",")) {
                String argument = part.strip();
                if (!argument.isBlank()) {
                    arguments.add(argument);
                }
            }
            if (!arguments.isEmpty()) {
                best = List.copyOf(arguments);
            }
        }
        return best;
    }

    private static String expressionPart(String source) {
        if (source == null) {
            return "";
        }
        String expression = source.strip();
        int comment = expression.indexOf('#');
        if (comment >= 0) {
            expression = expression.substring(0, comment).stripTrailing();
        }
        int equals = expression.indexOf('=');
        if (equals >= 0
                && (equals == 0 || "=!<>".indexOf(expression.charAt(equals - 1)) < 0)
                && (equals + 1 >= expression.length() || expression.charAt(equals + 1) != '=')) {
            expression = expression.substring(equals + 1).strip();
        }
        if (expression.startsWith("return ")) {
            expression = expression.substring("return ".length()).strip();
        }
        return expression;
    }

    private static boolean isKnownBuiltinCallable(String name) {
        return List.of("len", "list", "dict", "set", "tuple", "str", "int", "float", "bool",
                "sum", "min", "max", "range", "print", "input", "open", "enumerate", "zip", "map", "filter")
                .contains(name);
    }

    private static boolean expressionMatchesType(String expression, String type) {
        String value = expression == null ? "" : expression.strip();
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "int" -> value.matches("[-+]?\\d+");
            case "float" -> value.matches("[-+]?(?:\\d+\\.\\d*|\\d*\\.\\d+)(?:[eE][-+]?\\d+)?");
            case "str" -> isStringLiteral(value);
            case "list" -> value.startsWith("[") || value.startsWith("list(");
            case "tuple" -> value.startsWith("(") || value.startsWith("tuple(");
            case "dict" -> value.startsWith("{") || value.startsWith("dict(");
            case "set" -> value.startsWith("{") || value.startsWith("set(");
            case "nonetype" -> value.equals("None");
            case "bool" -> value.equals("True") || value.equals("False");
            default -> false;
        };
    }

    private static boolean isStringLiteral(String expression) {
        String value = expression == null ? "" : expression.strip();
        return value.matches("(?is)(?:[rubf]{0,2})'(?:[^'\\\\]|\\\\.)*'")
                || value.matches("(?is)(?:[rubf]{0,2})\"(?:[^\"\\\\]|\\\\.)*\"");
    }

    private static boolean isZeroLiteral(String expression) {
        String value = expression == null ? "" : expression.strip();
        return value.matches("[-+]?0+(?:\\.0+)?");
    }

    private static boolean isContainerType(String type) {
        return type.equals("list") || type.equals("tuple") || type.equals("dict")
                || type.equals("set") || type.equals("str") || type.equals("range");
    }

    private static boolean isNumericType(String type) {
        return type.equals("int") || type.equals("float") || type.equals("complex") || type.equals("bool");
    }

    private static boolean isLengthLikeName(String variable) {
        int lastDot = variable.lastIndexOf('.');
        String name = lastDot < 0 ? variable : variable.substring(lastDot + 1);
        String normalized = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        for (String part : normalized.split("_")) {
            if (part.equals("count") || part.equals("length") || part.equals("len")
                    || part.equals("size") || part.equals("num") || part.equals("number") || part.equals("total")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSimpleReference(String expression) {
        return Pattern.compile("[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*").matcher(expression).matches();
    }

    private static RuntimeRootCauseDiagnosis diagnoseMatrixShapeMismatch(String message, Frame tracebackFrame) {
        if (tracebackFrame == null || tracebackFrame.path().isBlank()) {
            return null;
        }
        Matcher dimensions = Pattern.compile(
                "mat1 and mat2 shapes cannot be multiplied \\(\\d+x(\\d+) and (\\d+)x\\d+\\)").matcher(message);
        if (!dimensions.find()) {
            return null;
        }
        int actualFeatures;
        int expectedFeatures;
        try {
            actualFeatures = Integer.parseInt(dimensions.group(1));
            expectedFeatures = Integer.parseInt(dimensions.group(2));
        } catch (NumberFormatException ignored) {
            return null;
        }
        if (actualFeatures == expectedFeatures) {
            return null;
        }
        Frame definition = findLayerDefinition(tracebackFrame, "nn\\.Linear", "in_features", expectedFeatures);
        if (definition == null) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                definition,
                "线性层要求输入最后一维是 " + expectedFeatures + "，但上一层实际输出最后一维是 "
                        + actualFeatures + "，两个维度不一致，因此矩阵无法相乘。",
                List.of(
                        "把 nn.Linear 的 in_features=" + expectedFeatures + " 改为 in_features=" + actualFeatures + "。",
                        "in_features 必须与上一层输出宽度一致；如果上一层是 RNN，通常应与 hidden_size 相同。",
                        "如果确实想使用 " + expectedFeatures + " 维，请同时调整上一层输出维度，而不能只修改 Linear。"
                ),
                Integer.toString(expectedFeatures),
                Integer.toString(actualFeatures),
                "in_features"
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseRnnInputMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "input\\.size\\(-1\\) must be equal to input_size\\. Expected (\\d+), got (\\d+)").matcher(message);
        if (!matcher.find()) {
            return null;
        }
        int expected = Integer.parseInt(matcher.group(1));
        int actual = Integer.parseInt(matcher.group(2));
        Frame definition = findLayerDefinition(tracebackFrame, "nn\\.(?:RNN|LSTM|GRU)", "input_size", expected);
        if (definition == null) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                definition,
                "循环层配置的 input_size 是 " + expected + "，但实际输入最后一维是 " + actual + "。",
                List.of(
                        "让 RNN/LSTM/GRU 的 input_size 与输入特征宽度一致；直接方案是把 " + expected + " 改为 " + actual + "。",
                        "如果输入来自 Embedding，也可以检查 embedding_dim 是否被误改；两处数值必须一致。"
                ),
                Integer.toString(expected),
                Integer.toString(actual),
                "input_size"
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseConvolutionChannelMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "expected input\\[[^]]+] to have (\\d+) channels, but got (\\d+) channels").matcher(message);
        if (!matcher.find()) {
            return null;
        }
        int expected = Integer.parseInt(matcher.group(1));
        int actual = Integer.parseInt(matcher.group(2));
        Frame definition = findLayerDefinition(tracebackFrame, "nn\\.Conv(?:1d|2d|3d)", "in_channels", expected);
        if (definition == null) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                definition,
                "卷积层配置为接收 " + expected + " 个通道，但实际输入有 " + actual + " 个通道。",
                List.of(
                        "检查 Conv 的 in_channels；若当前输入通道数正确，可把 " + expected + " 改为 " + actual + "。",
                        "如果模型原本应接收 " + expected + " 通道，请检查数据预处理或上一卷积层的输出通道。"
                ),
                Integer.toString(expected),
                Integer.toString(actual),
                "in_channels"
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseBatchNormMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile("running_mean should contain (\\d+) elements not (\\d+)").matcher(message);
        if (!matcher.find()) {
            return null;
        }
        int actual = Integer.parseInt(matcher.group(1));
        int configured = Integer.parseInt(matcher.group(2));
        Frame definition = findLayerDefinition(tracebackFrame, "nn\\.BatchNorm(?:1d|2d|3d)", "num_features", configured);
        if (definition == null) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                definition,
                "BatchNorm 配置了 " + configured + " 个特征，但实际输入包含 " + actual + " 个特征。",
                List.of(
                        "让 BatchNorm 的 num_features 与输入通道数一致；当前可把 " + configured + " 改为 " + actual + "。",
                        "如果 BatchNorm 配置原本正确，请检查上一层的 out_channels 是否被改动。"
                ),
                Integer.toString(configured),
                Integer.toString(actual),
                "num_features"
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseLayerNormMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "Given normalized_shape=\\[(\\d+)](?:[^\\n]*)input of size\\[([^]]+)]").matcher(message);
        if (!matcher.find()) {
            return null;
        }
        int expected = Integer.parseInt(matcher.group(1));
        String[] dimensions = matcher.group(2).trim().split("[,\\s]+");
        if (dimensions.length == 0) {
            return null;
        }
        int actual;
        try {
            actual = Integer.parseInt(dimensions[dimensions.length - 1]);
        } catch (NumberFormatException ignored) {
            return null;
        }
        Frame definition = findLayerDefinition(tracebackFrame, "nn\\.LayerNorm", "normalized_shape", expected);
        if (definition == null) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                definition,
                "LayerNorm 要求最后一维是 " + expected + "，但实际输入最后一维是 " + actual + "。",
                List.of(
                        "让 normalized_shape 与输入最后一维一致；当前可把 " + expected + " 改为 " + actual + "。",
                        "如果归一化维度原本正确，请检查上一层输出尺寸是否被误改。"
                ),
                Integer.toString(expected),
                Integer.toString(actual),
                "normalized_shape"
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseNumpyRequiresDetach(String message, Frame tracebackFrame) {
        if (tracebackFrame == null
                || !message.toLowerCase(Locale.ROOT).contains("requires grad")
                || !message.toLowerCase(Locale.ROOT).contains("detach")) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher numpyCall = Pattern.compile("([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\.numpy\\(\\)")
                .matcher(source);
        if (!numpyCall.find()) {
            return null;
        }
        String receiver = numpyCall.group(1);
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "这个 Tensor 正在参与梯度计算，不能直接转换为 NumPy 数组。",
                List.of(
                        "如果这里只用于查看、绘图或保存结果，先用 detach() 脱离计算图再转换。",
                        "不要在仍需反向传播的训练计算中间使用 NumPy。"
                ),
                receiver + ".numpy()",
                receiver + ".detach().numpy()",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseInvalidTensorShape(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "shape ['\"]([^'\"]+)['\"] is invalid for input of size (\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find() || tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher reshape = Pattern.compile("(?:reshape|view)\\s*\\(([^)]*)\\)").matcher(source);
        String shape = reshape.find() ? reshape.group(1).strip() : "";
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "reshape/view 请求的形状 “" + matcher.group(1) + "” 与张量总元素数 " + matcher.group(2) + " 不兼容。",
                List.of(
                        "目标各维度乘积必须等于原张量元素总数；最多只能有一个 -1 让 PyTorch 自动推断。",
                        "先打印原张量 shape 和 numel()，再决定正确的新形状。"
                ),
                shape,
                "",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseTensorScalarConversion(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "a Tensor with (\\d+) elements cannot be converted to Scalar",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find() || tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher itemCall = Pattern.compile("([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*)\\.item\\(\\)")
                .matcher(source);
        String target = itemCall.find() ? itemCall.group(1) + ".item()" : "";
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                ".item() 只能把单元素 Tensor 转成 Python 标量，但当前张量有 " + matcher.group(1) + " 个元素。",
                List.of(
                        "如果需要一个统计值，先明确使用 mean()、sum()、max() 等归约，再调用 item()。",
                        "如果需要全部元素，请保留 Tensor，或按用途转换为 list/NumPy，而不是调用 item()。"
                ),
                target,
                "",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseConvolutionInputRank(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "Expected (?:3D|4D).*input to conv2d, but got input of size:?\\s*(.+)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find() || tracebackFrame == null) {
            return null;
        }
        List<String> arguments = simpleCallArguments(tracebackFrame);
        String input = arguments.isEmpty() ? "" : arguments.get(0);
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "Conv2d 需要 [C,H,W] 或 [N,C,H,W]，但当前输入维数不正确：" + matcher.group(1) + "。",
                List.of(
                        "检查 DataLoader 是否已经添加 batch 维，以及图像通道是否位于正确位置。",
                        "只在确实缺少 batch 维时使用 unsqueeze(0)，不要盲目增加维度。"
                ),
                input,
                "",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseStackSizeMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "stack expects each tensor to be equal size, but got (.+) at entry (\\d+) and (.+) at entry (\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find()) {
            return null;
        }
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "同一批次中的样本形状不一致：第 " + matcher.group(2) + " 个是 " + matcher.group(1)
                        + "，第 " + matcher.group(4) + " 个是 " + matcher.group(3) + "。",
                List.of(
                        "在 Dataset/transform 中统一图片、序列或特征长度，或编写适合变长数据的 collate_fn。",
                        "不要在模型内部临时裁剪来掩盖数据批处理不一致。"
                ),
                "",
                "",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseTensorDtypeMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "mat1 and mat2 must have the same dtype, but got (\\w+) and (\\w+)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find() || tracebackFrame == null) {
            return null;
        }
        String actualType = matcher.group(1);
        String expectedType = matcher.group(2);
        String expectedMethod = tensorConversionMethod(expectedType);
        List<String> arguments = simpleCallArguments(tracebackFrame);
        String input = arguments.isEmpty() ? "" : arguments.get(0);
        if (input.isBlank() || !isSimpleReference(input)) {
            return new RuntimeRootCauseDiagnosis(
                    tracebackFrame,
                    "输入张量是 " + actualType + "，而模型权重是 " + expectedType + "，矩阵乘法要求数据类型一致。",
                    List.of("统一输入张量与模型参数的 dtype，并检查数据加载阶段是否意外产生了 float64。"),
                    input,
                    "",
                    ""
            );
        }

        Assignment assignment = findMostRecentAssignment(tracebackFrame, input);
        if (assignment != null && !expectedMethod.isBlank()) {
            String actualMethod = tensorConversionMethod(actualType);
            String suffix = actualMethod.isBlank() ? "" : "." + actualMethod + "()";
            if (!suffix.isBlank() && assignment.expression().endsWith(suffix)) {
                String replacement = assignment.expression().substring(0,
                        assignment.expression().length() - suffix.length()) + "." + expectedMethod + "()";
                return new RuntimeRootCauseDiagnosis(
                        assignment.location(),
                        "输入张量在这里被转换为 " + actualType + "，但后续线性层权重是 " + expectedType + "。",
                        List.of(
                                "把这次 dtype 转换改为与模型权重一致的 “." + expectedMethod + "()”。",
                                "也可以统一把整个模型转换为目标 dtype，但不要只改某一层。"
                        ),
                        assignment.expression(),
                        replacement,
                        input
                );
            }
        }
        String replacement = expectedMethod.isBlank() ? "" : input + "." + expectedMethod + "()";
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "输入张量是 " + actualType + "，而模型权重是 " + expectedType + "，矩阵乘法要求数据类型一致。",
                List.of(
                        "在进入该层前统一输入 dtype；常见情况是 NumPy 的 float64 进入了默认 float32 模型。",
                        "如果要使用更高精度，应统一转换整个模型和全部相关张量。"
                ),
                input,
                replacement,
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseExpectedScalarType(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "expected scalar type (\\w+) but found (\\w+)", Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find() || tracebackFrame == null) {
            return null;
        }
        String expected = matcher.group(1);
        String found = matcher.group(2);
        List<String> arguments = simpleCallArguments(tracebackFrame);
        String target = expected.equalsIgnoreCase("Long") && arguments.size() >= 2
                ? arguments.get(1)
                : (arguments.isEmpty() ? "" : arguments.get(0));
        String method = tensorConversionMethod(expected);
        String replacement = !target.isBlank() && !method.isBlank() ? target + "." + method + "()" : "";
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "当前张量类型是 " + found + "，但这个操作要求 " + expected + "。",
                List.of(
                        expected.equalsIgnoreCase("Long")
                                ? "分类损失的标签通常必须是 torch.long，并且保存类别编号而不是独热向量。"
                                : "统一参与运算的张量 dtype，避免只转换其中一个张量。",
                        "转换前先确认数据含义正确，不能用类型转换掩盖错误标签。"
                ),
                target,
                replacement,
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseTensorSizeMismatch(String message, Frame tracebackFrame) {
        Matcher matcher = Pattern.compile(
                "The size of tensor a \\((\\d+)\\) must match the size of tensor b \\((\\d+)\\) at non-singleton dimension ([-+]?\\d+)",
                Pattern.CASE_INSENSITIVE).matcher(message);
        if (!matcher.find() || tracebackFrame == null) {
            return null;
        }
        String source = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        String expression = expressionPart(source);
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "两个张量在第 " + matcher.group(3) + " 维的大小分别是 " + matcher.group(1)
                        + " 和 " + matcher.group(2) + "，该维不能广播。",
                List.of(
                        "分别打印参与运算张量的 shape，追查是哪一个上游层、切片或 reshape 改变了该维度。",
                        "只有确认该维本应为 1 时才使用 squeeze/unsqueeze；不要盲目 reshape。"
                ),
                expression,
                "",
                ""
        );
    }

    private static RuntimeRootCauseDiagnosis diagnoseDeviceMismatch(String message, Frame tracebackFrame) {
        if (!message.toLowerCase(Locale.ROOT).contains("same device") || tracebackFrame == null) {
            return null;
        }
        Matcher devices = Pattern.compile("(cuda(?::\\d+)?|cpu|mps)", Pattern.CASE_INSENSITIVE).matcher(message);
        List<String> foundDevices = new ArrayList<>();
        while (devices.find()) {
            String device = devices.group(1).toLowerCase(Locale.ROOT);
            if (!foundDevices.contains(device)) {
                foundDevices.add(device);
            }
        }
        if (foundDevices.size() < 2) {
            return null;
        }
        List<String> arguments = simpleCallArguments(tracebackFrame);
        String input = arguments.isEmpty() ? "" : arguments.get(0);
        return new RuntimeRootCauseDiagnosis(
                tracebackFrame,
                "参与同一运算的张量分别位于 " + String.join(" 和 ", foundDevices) + "，PyTorch 不能跨设备直接计算。",
                List.of(
                        "使用同一个 device 变量统一移动模型、输入、标签和新建张量。",
                        "特别检查 torch.zeros/ones/tensor 等临时张量，它们默认创建在 CPU。"
                ),
                input,
                "",
                ""
        );
    }

    private static String tensorConversionMethod(String torchTypeName) {
        return switch (torchTypeName.toLowerCase(Locale.ROOT)) {
            case "float", "float32" -> "float";
            case "double", "float64" -> "double";
            case "half", "float16" -> "half";
            case "long", "int64" -> "long";
            case "int", "int32" -> "int";
            case "bool" -> "bool";
            default -> "";
        };
    }

    private static int locateHighlightColumn(String sourceLine, String target, String anchor) {
        if (sourceLine == null || sourceLine.isBlank() || target == null || target.isBlank()) {
            return -1;
        }
        if (anchor != null && !anchor.isBlank()) {
            Matcher anchored = Pattern.compile(
                    "\\b" + Pattern.quote(anchor) + "\\s*=\\s*(?:\\[\\s*)?" + Pattern.quote(target) + "\\b")
                    .matcher(sourceLine);
            if (anchored.find()) {
                int targetOffset = anchored.group().lastIndexOf(target);
                if (targetOffset >= 0) {
                    return anchored.start() + targetOffset;
                }
            }
        }
        int first = sourceLine.indexOf(target);
        return first >= 0 && first == sourceLine.lastIndexOf(target) ? first : -1;
    }

    private static Frame findLayerDefinition(Frame tracebackFrame,
                                             String layerClassPattern,
                                             String parameterName,
                                             int configuredValue) {
        if (tracebackFrame == null || tracebackFrame.path().isBlank()) {
            return null;
        }
        String callSource = readSourceLine(tracebackFrame.path(), tracebackFrame.line());
        Matcher attributeMatcher = Pattern.compile("self\\.([A-Za-z_][\\w]*)\\s*\\(").matcher(callSource);
        String layerAttribute = attributeMatcher.find() ? attributeMatcher.group(1) : "";
        try {
            List<String> lines = Files.readAllLines(Paths.get(tracebackFrame.path()), StandardCharsets.UTF_8);
            Frame fallback = null;
            for (int i = 0; i < lines.size(); i++) {
                if (!Pattern.compile(layerClassPattern).matcher(lines.get(i)).find()) {
                    continue;
                }
                int end = layerStatementEnd(lines, i);
                String block = String.join("\n", lines.subList(i, end + 1));
                if (!declaresLayerParameter(block, layerClassPattern, parameterName, configuredValue)) {
                    continue;
                }
                int parameterLine = parameterLineInBlock(
                        block, i, layerClassPattern, parameterName, configuredValue);
                Frame candidate = new Frame(tracebackFrame.path(), parameterLine + 1, "__init__", 0, 0);
                if (fallback == null) {
                    fallback = candidate;
                }
                if (!layerAttribute.isBlank() && block.contains("self." + layerAttribute)) {
                    return candidate;
                }
                i = end;
            }
            return fallback;
        } catch (IOException | InvalidPathException ignored) {
            return null;
        }
    }

    private static int layerStatementEnd(List<String> lines, int start) {
        int balance = 0;
        boolean opened = false;
        int maximum = Math.min(lines.size() - 1, start + 30);
        for (int i = start; i <= maximum; i++) {
            String line = lines.get(i);
            for (int j = 0; j < line.length(); j++) {
                char character = line.charAt(j);
                if (character == '(') {
                    balance++;
                    opened = true;
                } else if (character == ')') {
                    balance--;
                }
            }
            if (opened && balance <= 0) {
                return i;
            }
        }
        return start;
    }

    private static int parameterLineInBlock(String block,
                                            int blockStartLine,
                                            String layerClassPattern,
                                            String parameterName,
                                            int configuredValue) {
        String value = Pattern.quote(Integer.toString(configuredValue));
        Matcher keyword = Pattern.compile(
                "\\b" + Pattern.quote(parameterName) + "\\s*=\\s*(?:\\[\\s*)?(" + value + ")\\b")
                .matcher(block);
        if (keyword.find()) {
            return blockStartLine + countNewlines(block, keyword.start(1));
        }
        Matcher positional = Pattern.compile(
                layerClassPattern + "\\s*\\(\\s*(?:\\[\\s*)?(" + value + ")\\b")
                .matcher(block);
        if (positional.find()) {
            return blockStartLine + countNewlines(block, positional.start(1));
        }
        return blockStartLine;
    }

    private static int countNewlines(String text, int endExclusive) {
        int count = 0;
        for (int i = 0; i < Math.min(endExclusive, text.length()); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static boolean declaresLayerParameter(String line,
                                                  String layerClassPattern,
                                                  String parameterName,
                                                  int configuredValue) {
        String value = Pattern.quote(Integer.toString(configuredValue));
        return Pattern.compile("\\b" + Pattern.quote(parameterName) + "\\s*=\\s*(?:\\[\\s*)?" + value + "\\b")
                .matcher(line).find()
                || Pattern.compile(layerClassPattern + "\\s*\\(\\s*(?:\\[\\s*)?" + value + "\\b")
                .matcher(line).find();
    }

    private static String[] reshapeReplacement(Frame frame) {
        if (frame == null) {
            return new String[]{"output[-1]", "output.shape[-1]"};
        }
        String source = readSourceLine(frame.path(), frame.line());
        Matcher matcher = Pattern.compile("([A-Za-z_][\\w.]*)\\[-1]").matcher(source);
        String lastMatch = "";
        while (matcher.find()) {
            lastMatch = matcher.group(1);
        }
        if (lastMatch.isBlank()) {
            return new String[]{"output[-1]", "output.shape[-1]"};
        }
        return new String[]{lastMatch + "[-1]", lastMatch + ".shape[-1]"};
    }

    private static String[] parametersCallReplacement(Frame frame) {
        if (frame == null) {
            return new String[]{"model.parameters", "model.parameters()"};
        }
        String source = readSourceLine(frame.path(), frame.line());
        Matcher matcher = Pattern.compile("([A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*\\.parameters)(?!\\s*\\()").matcher(source);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(1) + "()"};
        }
        return new String[]{"model.parameters", "model.parameters()"};
    }

    private static String caretTargetFollowingFrame(String output, Frame frame) {
        int sourceStart = output.indexOf('\n', frame.end());
        if (sourceStart < 0) {
            return "";
        }
        sourceStart++;
        int sourceEnd = output.indexOf('\n', sourceStart);
        if (sourceEnd < 0) {
            return "";
        }
        int markerStart = sourceEnd + 1;
        int markerEnd = output.indexOf('\n', markerStart);
        if (markerEnd < 0) {
            markerEnd = output.length();
        }
        String sourceLine = output.substring(sourceStart, sourceEnd);
        String markerLine = output.substring(markerStart, markerEnd);
        int firstMarker = -1;
        int lastMarker = -1;
        for (int i = 0; i < markerLine.length(); i++) {
            char character = markerLine.charAt(i);
            if (character == '^' || character == '~') {
                if (firstMarker < 0) {
                    firstMarker = i;
                }
                lastMarker = i + 1;
            }
        }
        if (firstMarker < 0 || firstMarker >= sourceLine.length()) {
            return "";
        }
        String target = sourceLine.substring(firstMarker, Math.min(lastMarker, sourceLine.length())).strip();
        if (!target.isBlank()) {
            return target;
        }
        return String.valueOf(sourceLine.charAt(firstMarker));
    }

    private static String quotedValue(String message) {
        Matcher matcher = Pattern.compile("['\\\"]([^'\\\"]+)['\\\"]").matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String lastQuotedValue(String message) {
        Matcher matcher = Pattern.compile("['\\\"]([^'\\\"]+)['\\\"]").matcher(message);
        String value = "";
        while (matcher.find()) {
            value = matcher.group(1);
        }
        return value;
    }

    private static String didYouMean(String message) {
        Matcher matcher = Pattern.compile(
                "Did you mean(?::|\\s+)\\s*['\\\"]([^'\\\"]+)['\\\"]", Pattern.CASE_INSENSITIVE)
                .matcher(message);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String stripAnsi(String text) {
        return ANSI_PATTERN.matcher(text == null ? "" : text).replaceAll("");
    }

    private static String tail(String text, int maximumLength) {
        return text.length() <= maximumLength ? text : "…（前面的输出已省略）…\n" + text.substring(text.length() - maximumLength);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private record Frame(String path, int line, String function, int start, int end) {
    }

    private record ExceptionDetails(String type, String message) {
    }

    private record SourceFileScan(boolean available, int lineCount, String description) {
    }

    private record Assignment(Frame location, String expression) {
    }

    private record StateDictSizeMismatch(String parameterName,
                                         List<Integer> checkpointShape,
                                         List<Integer> currentShape) {
    }

    private record LayerParameterLocation(Frame location, String highlightText, String highlightAnchor) {
    }

    private record RuntimeRootCauseDiagnosis(Frame location, String explanation, List<String> suggestions,
                                              String highlightText, String replacementText,
                                              String highlightAnchor) {
    }

    private record Advice(String explanation, List<String> suggestions, String highlightText, String replacementText,
                          String highlightAnchor, Frame locationOverride) {
    }
}
