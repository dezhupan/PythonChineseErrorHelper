package com.codex.pyerrorhelper;

import java.util.regex.Pattern;

final class MissingParametersCallDetector {
    private static final Pattern PARAMETERS_REFERENCE = Pattern.compile(
            "[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*\\.parameters");
    private static final Pattern OPTIMIZER_CALL = Pattern.compile(
            "(?s)(?:torch\\.optim\\.|optim\\.)?(?:Adam|AdamW|SGD|RMSprop|Adagrad|Adadelta|ASGD|LBFGS|NAdam|RAdam|SparseAdam)\\s*\\(");

    private MissingParametersCallDetector() {
    }

    static boolean isMissingCall(String referenceText, String immediateParentText, String surroundingText) {
        if (referenceText == null || !PARAMETERS_REFERENCE.matcher(referenceText).matches()) {
            return false;
        }
        String parent = immediateParentText == null ? "" : immediateParentText.stripLeading();
        if (parent.startsWith(referenceText + "(")) {
            return false;
        }
        return surroundingText != null && OPTIMIZER_CALL.matcher(surroundingText).find();
    }
}
