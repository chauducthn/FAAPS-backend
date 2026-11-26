package vn.edu.fpt.faaps.user.service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

final class AdminUserImportLoader {

    private static final Pattern SEMESTER_CODE = Pattern.compile("^(SP|SU|FA)(\\d{4})$");

    record CohortInfo(String id, String majorId, String majorCode, String version,
            String status, String majorStatus, int maxTerms) {
    }

    record LoadedRefs(Map<String, String> roleIds, Set<String> emails, Set<String> studentCodes,
            Map<String, String> majorIds, Map<String, CohortInfo> cohorts,
            Map<String, String> semesters, String currentSemesterCode) {
    }

    private AdminUserImportLoader() {
    }

    static LoadedRefs preload(JdbcTemplate jdbc) {
        Map<String, String> roleIds = new HashMap<>();
        jdbc.query("SELECT code, id FROM roles", rs -> {
            roleIds.put(rs.getString("code").toUpperCase(Locale.ROOT), rs.getString("id"));
        });
        Set<String> emails = new HashSet<>(jdbc.queryForList("SELECT LOWER(email) FROM users", String.class));
        Set<String> studentCodes = new HashSet<>(
                jdbc.queryForList("SELECT UPPER(student_code) FROM students", String.class));
        Map<String, String> majorIds = new HashMap<>();
        jdbc.query("SELECT id, UPPER(code) AS code FROM majors", rs -> {
            majorIds.put(rs.getString("code"), rs.getString("id"));
        });
        Map<String, CohortInfo> cohorts = new HashMap<>();
        jdbc.query("""
                SELECT cv.id, cv.major_id, UPPER(m.code) AS major_code, UPPER(cv.version) AS version,
                       cv.status, m.status AS major_status,
                       COALESCE((SELECT MAX(cc.semester_number) FROM curriculum_courses cc
                                 WHERE cc.curriculum_version_id = cv.id), 0) AS max_terms
                FROM curriculum_versions cv JOIN majors m ON m.id = cv.major_id
                """, rs -> {
            CohortInfo info = new CohortInfo(rs.getString("id"), rs.getString("major_id"),
                    rs.getString("major_code"), rs.getString("version"), rs.getString("status"),
                    rs.getString("major_status"), rs.getInt("max_terms"));
            cohorts.put(info.majorCode() + "|" + info.version(), info);
        });
        Map<String, String> semesters = new HashMap<>();
        jdbc.query("SELECT id, UPPER(code) AS code FROM semesters", rs -> {
            semesters.put(rs.getString("code"), rs.getString("id"));
        });
        List<String> cur = jdbc.queryForList(
                "SELECT code FROM semesters WHERE start_date <= ? ORDER BY start_date DESC LIMIT 1",
                String.class, Date.valueOf(LocalDate.now()));
        return new LoadedRefs(roleIds, emails, studentCodes, majorIds, cohorts, semesters, cur.isEmpty() ? null : cur.get(0));
    }

    static String ensureSemester(JdbcTemplate jdbc, Map<String, String> semesters, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        String code = rawCode.strip().toUpperCase(Locale.ROOT);
        String existing = semesters.get(code);
        if (existing != null) {
            return existing;
        }
        Matcher m = SEMESTER_CODE.matcher(code);
        if (!m.matches()) {
            return null;
        }
        String term = m.group(1);
        int year = Integer.parseInt(m.group(2));
        String name = switch (term) {
            case "SP" -> "Spring " + year;
            case "SU" -> "Summer " + year;
            default -> "Fall " + year;
        };
        LocalDate start = switch (term) {
            case "SP" -> LocalDate.of(year, 1, 1);
            case "SU" -> LocalDate.of(year, 5, 1);
            default -> LocalDate.of(year, 9, 1);
        };
        LocalDate end = switch (term) {
            case "SP" -> LocalDate.of(year, 4, 30);
            case "SU" -> LocalDate.of(year, 8, 31);
            default -> LocalDate.of(year, 12, 31);
        };
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO semesters (id, code, name, start_date, end_date, is_current, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, code, name, Date.valueOf(start), Date.valueOf(end));
        semesters.put(code, id);
        return id;
    }

    static final String INSERT_USER_SQL = """
            INSERT INTO users (
                id, role_id, email, password_hash, auth_provider,
                full_name, phone, status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'LOCAL', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    static final String INSERT_STUDENT_SQL = """
            INSERT INTO students (
                user_id, student_code, major_id, curriculum_version_id,
                enrollment_semester_id, expected_graduation_semester_id, current_semester,
                current_gpa, total_credits_earned, academic_status, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    static final String INSERT_ADVISOR_SQL = """
            INSERT INTO advisors (user_id, advisor_code, max_advisees, status, created_at, updated_at)
            VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """;

    static final String INSERT_COHORT_ADVISOR_SQL = """
            INSERT INTO cohort_advisors (cohort_id, advisor_id, is_active)
            VALUES (?, ?, TRUE)
            """;

    static String normalizeFullName(String value, int rowNum) {
        String clean = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 255) {
            throw new IllegalArgumentException(
                    "Row " + rowNum + ": fullName is required and must be at most 255 characters.");
        }
        return clean;
    }

    static String normalizePhone(String phone, int rowNum) {
        String clean = phone == null || phone.isBlank() ? null : phone.strip();
        if (clean != null && !vn.edu.fpt.faaps.academic.service.StudentOnboardingService.PHONE.matcher(clean).matches()) {
            throw new IllegalArgumentException(
                    "Row " + rowNum + ": phone must be a 10-digit Vietnamese number starting with 0.");
        }
        return clean;
    }

    static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(java.util.Locale.ROOT);
    }
}
