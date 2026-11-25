package vn.edu.fpt.faaps.academic.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceRule;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.CourseAttendanceOverride;

final class AttendanceConfigStore {
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final AttendanceSchemaInitializer schema;

    AttendanceConfigStore(JdbcTemplate jdbc, AttendanceSchemaInitializer schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    AttendanceRule getDefaultRule() {
        schema.ensureSchema();
        List<AttendanceRule> rules = jdbc.query("""
                SELECT total_sessions, min_attendance_percent, warn_absent_sessions
                FROM attendance_default_config WHERE singleton_id = 1
                """, (rs, row) -> new AttendanceRule(
                rs.getInt("total_sessions"),
                rs.getInt("min_attendance_percent"),
                rs.getInt("warn_absent_sessions")));
        return rules.isEmpty() ? new AttendanceRule(20, 80, 3) : rules.get(0);
    }

    AttendanceRule saveDefaultRule(AttendanceRule rule) {
        AdminAttendanceService.validateRule(rule);
        schema.ensureSchema();
        int updated = jdbc.update("""
                UPDATE attendance_default_config
                SET total_sessions = ?, min_attendance_percent = ?, warn_absent_sessions = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE singleton_id = 1
                """, rule.totalSessions(), rule.minAttendancePercent(), rule.warnAbsentSessions());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO attendance_default_config
                    (singleton_id, total_sessions, min_attendance_percent, warn_absent_sessions, updated_at)
                    VALUES (1, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, rule.totalSessions(), rule.minAttendancePercent(), rule.warnAbsentSessions());
        }
        return getDefaultRule();
    }

    List<CourseAttendanceOverride> listOverrides() {
        schema.ensureSchema();
        return jdbc.query("""
                SELECT cfg.course_id, c.code AS course_code, c.name AS course_name,
                       cfg.total_sessions, cfg.min_attendance_percent, cfg.warn_absent_sessions, cfg.updated_at
                FROM course_attendance_configs cfg
                JOIN courses c ON c.id = cfg.course_id
                ORDER BY c.code ASC
                """, (rs, row) -> {
            Timestamp ts = rs.getTimestamp("updated_at");
            return new CourseAttendanceOverride(
                    rs.getString("course_id"),
                    rs.getString("course_code"),
                    rs.getString("course_name"),
                    rs.getInt("total_sessions"),
                    rs.getInt("min_attendance_percent"),
                    rs.getInt("warn_absent_sessions"),
                    ts != null ? ts.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT));
        });
    }

    CourseAttendanceOverride saveOverride(UUID courseId, AttendanceRule rule) {
        if (courseId == null) {
            throw new IllegalArgumentException("courseId is required");
        }
        AdminAttendanceService.validateRule(rule);
        schema.ensureSchema();
        List<String[]> courseRows = jdbc.query(
                "SELECT code, name FROM courses WHERE id = ?",
                (rs, row) -> new String[] { rs.getString("code"), rs.getString("name") },
                courseId.toString());
        if (courseRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }

        int updated = jdbc.update("""
                UPDATE course_attendance_configs
                SET total_sessions = ?, min_attendance_percent = ?, warn_absent_sessions = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE course_id = ?
                """, rule.totalSessions(), rule.minAttendancePercent(), rule.warnAbsentSessions(), courseId.toString());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO course_attendance_configs
                    (course_id, total_sessions, min_attendance_percent, warn_absent_sessions, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, courseId.toString(), rule.totalSessions(), rule.minAttendancePercent(),
                    rule.warnAbsentSessions());
        }
        return new CourseAttendanceOverride(
                courseId.toString(),
                courseRows.get(0)[0],
                courseRows.get(0)[1],
                rule.totalSessions(),
                rule.minAttendancePercent(),
                rule.warnAbsentSessions(),
                LocalDateTime.now().format(ISO_DT));
    }

    void removeOverride(UUID courseId) {
        schema.ensureSchema();
        jdbc.update("DELETE FROM course_attendance_configs WHERE course_id = ?", courseId.toString());
    }

    static AttendanceRule ruleForCourse(String courseCode, AttendanceRule defaultRule,
            List<CourseAttendanceOverride> overrides) {
        for (CourseAttendanceOverride o : overrides) {
            if (o.courseCode() != null && o.courseCode().equalsIgnoreCase(courseCode)) {
                return new AttendanceRule(o.totalSessions(), o.minAttendancePercent(), o.warnAbsentSessions());
            }
        }
        return defaultRule;
    }
}
