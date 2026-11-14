package vn.edu.fpt.faaps.common.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Standard validator and normalizer for student identifiers across FAAPS.
 * Format requirement: 2 uppercase letters followed by 6 digits (e.g. SE173012, IA160343).
 */
public final class StudentCodeValidator {

    private static final Pattern STUDENT_CODE_PATTERN =
            Pattern.compile("^[A-Z]{2}(0[1-9]|1\\d|2[0-2])\\d{4}$");

    private StudentCodeValidator() {
    }

    /**
     * Validates student code format and normalizes it (trimmed and uppercase).
     *
     * @param studentCode raw student code input
     * @return trimmed, upper-cased student code
     * @throws IllegalArgumentException if null, blank, or invalid format
     */
    public static String validateAndNormalize(String studentCode) {
        if (studentCode == null || studentCode.isBlank()) {
            throw new IllegalArgumentException("studentCode is required");
        }
        String normalized = studentCode.strip().toUpperCase(Locale.ROOT);
        if (!STUDENT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "studentCode must be 2 letters, a cohort from 01 to 22 and 4 digits (e.g. SE190001)");
        }

        // =========================================================================================
        // [EMPTY REGION / EXTENSION HOOK]: Stricter Cohort & Major Database Check
        // -----------------------------------------------------------------------------------------
        // Reserved for future DB verification:
        // e.g. validating cohort digits (digits 3-4 like '16' in 'SE160343') against active intake
        // cohorts in the database, major code prefix mapping (e.g. 'SE' -> Software Engineering),
        // or blacklist / reservation rules.
        // =========================================================================================

        return normalized;
    }

    /**
     * Checks whether the given student code matches the expected format pattern.
     *
     * @param studentCode raw student code input
     * @return true if valid format, false otherwise
     */
    public static boolean isValidFormat(String studentCode) {
        if (studentCode == null || studentCode.isBlank()) {
            return false;
        }
        return STUDENT_CODE_PATTERN.matcher(studentCode.strip().toUpperCase(Locale.ROOT)).matches();
    }
}
