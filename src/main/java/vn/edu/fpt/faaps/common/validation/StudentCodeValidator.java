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

        return normalized;
    }

    /**
     * Ensures the first two letters and next two digits of the student code match the cohort's major and version.
     */
    public static void validateMajorAndCohortMatch(String normalizedStudentCode, String majorCode, String cohortVersion) {
        if (normalizedStudentCode == null || normalizedStudentCode.length() < 4) {
            return;
        }
        String prefix = normalizedStudentCode.substring(0, 2).toUpperCase(Locale.ROOT);
        String studentCohort = "K" + normalizedStudentCode.substring(2, 4);
        if (majorCode != null && !majorCode.isBlank()) {
            String expectedMajor = majorCode.strip().toUpperCase(Locale.ROOT);
            if (!prefix.equals(expectedMajor)) {
                throw new IllegalArgumentException(
                        "Student ID prefix '" + prefix + "' must match cohort major code '" + expectedMajor + "'");
            }
        }
        if (cohortVersion != null && !cohortVersion.isBlank()) {
            String expectedCohort = cohortVersion.strip().toUpperCase(Locale.ROOT);
            if (!studentCohort.equals(expectedCohort)) {
                throw new IllegalArgumentException(
                        "Student ID cohort '" + studentCohort + "' must match cohort '" + expectedCohort + "'");
            }
        }
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
