package com.codex.pyerrorhelper;

import java.util.List;

public record ErrorAnalysis(
        String runName,
        String analysisScope,
        String exceptionType,
        String originalMessage,
        String chineseExplanation,
        List<String> suggestions,
        String filePath,
        int lineNumber,
        String functionName,
        String sourceLine,
        String highlightText,
        int highlightColumn,
        String replacementText,
        boolean tracedToRootCause,
        String rawTraceback
) {
    public String locationText() {
        if (filePath == null || filePath.isBlank()) {
            return "未能从报错信息中识别文件位置";
        }
        String location = filePath + (lineNumber > 0 ? ":" + lineNumber : "");
        if (functionName != null && !functionName.isBlank() && !"<module>".equals(functionName)) {
            location += "（函数 " + functionName + "）";
        }
        return location;
    }

    public String toPlainText() {
        StringBuilder text = new StringBuilder();
        text.append("Python 中文报错助手\n\n");
        text.append("运行项：").append(runName).append('\n');
        text.append("分析范围：").append(analysisScope).append('\n');
        text.append("错误：").append(exceptionType);
        if (!originalMessage.isBlank()) {
            text.append(": ").append(originalMessage);
        }
        text.append("\n原始信息翻译解释：").append(chineseExplanation).append("\n\n");
        text.append("位置：").append(locationText()).append("\n\n");
        if (!sourceLine.isBlank()) {
            text.append("出错代码：\n").append(sourceLine).append("\n\n");
        }
        if (!replacementText.isBlank()) {
            text.append("建议替换：").append(highlightText).append(" → ").append(replacementText).append("\n\n");
        }
        text.append("建议修改：\n");
        for (int i = 0; i < suggestions.size(); i++) {
            text.append(i + 1).append(". ").append(suggestions.get(i)).append('\n');
        }
        text.append("\n原始报错：\n").append(rawTraceback);
        return text.toString();
    }
}
