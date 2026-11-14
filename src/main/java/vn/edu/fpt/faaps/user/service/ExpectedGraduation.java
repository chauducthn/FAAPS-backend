package vn.edu.fpt.faaps.user.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Works out the semester a student is expected to graduate in. It is calculated, never typed by an admin: a
 * hand-picked value can be impossible for the student (a first-semester student graduating next summer).
 *
 * <p>The curriculum has N terms (the highest {@code semester_number} of its courses). A student who starts in the
 * enrollment semester and keeps the normal pace finishes {@code N - 1} terms later. A student whose current
 * semester is behind the number of terms that have already passed since enrollment is late by the difference, and
 * the graduation moves back by the same amount. Semesters follow Spring, Summer, Fall of every year.
 */
public final class ExpectedGraduation {
    private static final String[] ORDER = {"SP", "SU", "FA"};
    private static final Pattern CODE = Pattern.compile("^(SP|SU|FA)(\\d{4})$");

    private ExpectedGraduation() {
    }

    /**
     * The id of the expected graduation semester, or null when it cannot be known: the curriculum has no courses
     * yet, a semester code is not in the SP/SU/FA form, or the semester does not exist in the catalogue yet.
     */
    public static String semesterId(JdbcTemplate jdbc, UUID curriculumVersionId, UUID enrollmentSemesterId,
            int currentSemester) {
        try {
            Integer terms = jdbc.queryForObject("""
                    SELECT COALESCE(MAX(semester_number), 0) FROM curriculum_courses WHERE curriculum_version_id = ?
                    """, Integer.class, curriculumVersionId.toString());
            String enrollment = firstOrNull(jdbc.queryForList("SELECT code FROM semesters WHERE id = ?",
                    String.class, enrollmentSemesterId.toString()));
            String current = firstOrNull(jdbc.queryForList(
                    "SELECT code FROM semesters WHERE start_date <= ? ORDER BY start_date DESC LIMIT 1",
                    String.class, java.sql.Date.valueOf(LocalDate.now())));
            String code = code(enrollment, current, currentSemester, terms == null ? 0 : terms);
            if (code == null) {
                return null;
            }
            return firstOrNull(jdbc.queryForList("SELECT id FROM semesters WHERE UPPER(code) = ?", String.class,
                    code.toUpperCase(Locale.ROOT)));
        } catch (DataAccessException exception) {
            // The value is derived information: a missing table or column must not stop an account from being created.
            return null;
        }
    }

    /** The semester code (for example SU2029), or null when it cannot be worked out. */
    static String code(String enrollmentCode, String currentCode, int currentSemester, int curriculumSemesters) {
        if (enrollmentCode == null || currentSemester < 1 || curriculumSemesters < 1) {
            return null;
        }
        Integer passed = distance(enrollmentCode, currentCode);
        int termsSinceEnrollment = passed == null ? currentSemester : Math.max(passed + 1, 1);
        int delay = Math.max(0, termsSinceEnrollment - currentSemester);
        return addSemesters(enrollmentCode, curriculumSemesters - 1 + delay);
    }

    /** The semester {@code steps} terms after {@code code} (SP, SU, FA, then SP of the next year). */
    static String addSemesters(String code, int steps) {
        int index = index(code) == null ? Integer.MIN_VALUE : index(code) + steps;
        if (index == Integer.MIN_VALUE) {
            return null;
        }
        return ORDER[Math.floorMod(index, 3)] + Math.floorDiv(index, 3);
    }

    private static Integer distance(String from, String to) {
        Integer a = index(from);
        Integer b = index(to);
        return a == null || b == null ? null : b - a;
    }

    private static Integer index(String code) {
        if (code == null) {
            return null;
        }
        Matcher matcher = CODE.matcher(code.strip().toUpperCase(Locale.ROOT));
        if (!matcher.matches()) {
            return null;
        }
        return Integer.parseInt(matcher.group(2)) * 3 + List.of(ORDER).indexOf(matcher.group(1));
    }

    private static String firstOrNull(List<String> values) {
        return values.isEmpty() ? null : values.get(0);
    }
}
