package com.collect.platform.audit;

import java.util.regex.Pattern;

public final class Masker {

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(password\\s*[=:]\\s*)([^,;\\s]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(token\\s*[=:]\\s*)([^,;\\s]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(secret\\s*[=:]\\s*)([^,;\\s]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(api[_-]?key\\s*[=:]\\s*)([^,;\\s]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(Authorization\\s*[=:]\\s*)([^,;]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(Cookie\\s*[=:]\\s*)([^,;]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(Bearer\\s+)([A-Za-z0-9._\\-]+)", Pattern.CASE_INSENSITIVE)
    };

    private Masker() {
    }

    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        for (Pattern p : PATTERNS) {
            out = p.matcher(out).replaceAll("$1***");
        }
        return out;
    }
}
