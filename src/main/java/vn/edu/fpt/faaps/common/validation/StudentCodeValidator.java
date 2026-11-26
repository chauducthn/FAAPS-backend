package vn.edu.fpt.faaps.common.validation;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Standard validator and normalizer for FPT University student identifiers across FAAPS.
 * Format: Campus (Q/D/H/S/C) + Program Group (E=Engineering, S=Studies/Business, A=Arts/Languages)
 * + 2-digit cohort (01-22) + 4 digits (e.g. SE182965, SS209938, HE190001).
 */
public final class StudentCodeValidator {

    private static final Pattern STUDENT_CODE_PATTERN =
            Pattern.compile("^[QDHSC][ESA](0[1-9]|1\\d|2[0-2])\\d{4}$");

    private StudentCodeValidator() {
    }

    public static String validateAndNormalize(String studentCode) {
        if (studentCode == null || studentCode.isBlank()) {
            throw new IllegalArgumentException("studentCode is required");
        }
        String normalized = studentCode.strip().toUpperCase(Locale.ROOT);
        if (!STUDENT_CODE_PATTERN.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "studentCode must start with campus (Q/D/H/S/C) + group (E/S/A) + cohort 01-22 + 4 digits (e.g. SE182965, SS209938)");
        }
        return normalized;
    }

    /**
     * Ensures the 2-digit cohort in the student code (e.g. 19 in SE190001 -> K19) matches the cohort version.
     * Note: the first two letters encode Campus + Program Group, NOT the major code.
     */
    public static void validateMajorAndCohortMatch(String normalizedStudentCode, String majorCode, String cohortVersion) {
        if (normalizedStudentCode == null || normalizedStudentCode.length() < 4) {
            return;
        }
        String studentCohort = "K" + Integer.parseInt(normalizedStudentCode.substring(2, 4));
        if (cohortVersion != null && !cohortVersion.isBlank()) {
            String expectedCohort = cohortVersion.strip().toUpperCase(Locale.ROOT);
            if (!studentCohort.equals(expectedCohort)) {
                throw new IllegalArgumentException(
                        "Student ID cohort '" + studentCohort + "' must match cohort '" + expectedCohort + "'");
            }
        }
    }

    public static boolean isValidFormat(String studentCode) {
        if (studentCode == null || studentCode.isBlank()) {
            return false;
        }
        return STUDENT_CODE_PATTERN.matcher(studentCode.strip().toUpperCase(Locale.ROOT)).matches();
    }
}
