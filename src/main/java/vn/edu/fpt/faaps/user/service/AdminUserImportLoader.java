package vn.edu.fpt.faaps.user.service;

import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

final class AdminUserImportLoader {

    record CohortInfo(String id, String majorId, String majorCode, String version,
            String status, String majorStatus, int maxTerms) {
    }

    record LoadedRefs(Map<String, String> roleIds, Set<String> emails, Set<String> studentCodes,
            Map<String, CohortInfo> cohorts, Map<String, String> semesters, String currentSemesterCode) {
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
        return new LoadedRefs(roleIds, emails, studentCodes, cohorts, semesters, cur.isEmpty() ? null : cur.get(0));
    }
}
