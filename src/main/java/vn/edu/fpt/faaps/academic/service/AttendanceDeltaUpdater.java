package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceChangeNotification;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportRow;

final class AttendanceDeltaUpdater {
    private static final Logger log = LoggerFactory.getLogger(AttendanceDeltaUpdater.class);

    private final JdbcTemplate jdbc;

    AttendanceDeltaUpdater(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * OnChanged delta check: calculates % absent against total sessions of the course,
     * and only triggers a notification/email event when % absent has strictly increased.
     */
    AttendanceChangeNotification applyAttendanceRowWithDeltaCheck(AttendanceImportRow row) {
        String studentId = findSingleId("SELECT user_id FROM students WHERE UPPER(student_code) = ?",
                row.studentCode().strip().toUpperCase(Locale.ROOT));
        String courseId = findSingleId("SELECT id FROM courses WHERE UPPER(code) = ?",
                row.courseCode().strip().toUpperCase(Locale.ROOT));
        String semesterId = findSingleId("SELECT id FROM semesters WHERE UPPER(code) = ?",
                row.semesterCode().strip().toUpperCase(Locale.ROOT));

        if (studentId == null || courseId == null || semesterId == null) {
            throw new IllegalArgumentException(
                    "Invalid attendance row: student, course, or semester does not exist.");
        }

        BigDecimal newAbsentRate = BigDecimal.valueOf(row.absentSessions() * 100.0 / row.totalSessions())
                .setScale(2, RoundingMode.HALF_UP);

        List<ExistingAttendance> existing = jdbc.query("""
                SELECT id, absent_sessions, absent_rate
                FROM student_course_attendance
                WHERE student_id = ? AND course_id = ? AND semester_id = ?
                FOR UPDATE
                """, (rs, r) -> new ExistingAttendance(
                rs.getString("id"),
                rs.getInt("absent_sessions"),
                rs.getBigDecimal("absent_rate")), studentId, courseId, semesterId);

        boolean absenceIncreased;
        BigDecimal oldAbsentRate;

        if (existing.isEmpty()) {
            oldAbsentRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            absenceIncreased = newAbsentRate.compareTo(BigDecimal.ZERO) > 0;
            jdbc.update("""
                    INSERT INTO student_course_attendance
                    (id, student_id, course_id, semester_id, total_sessions, absent_sessions,
                     absent_rate, result, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), studentId, courseId, semesterId,
                    row.totalSessions(), row.absentSessions(), newAbsentRate, row.result());
        } else {
            ExistingAttendance prev = existing.get(0);
            oldAbsentRate = prev.absentRate() == null ? BigDecimal.ZERO : prev.absentRate();
            absenceIncreased = newAbsentRate.compareTo(oldAbsentRate) > 0;
            if (newAbsentRate.compareTo(oldAbsentRate) != 0 || prev.absentSessions() != row.absentSessions()) {
                jdbc.update("""
                        UPDATE student_course_attendance
                        SET total_sessions = ?, absent_sessions = ?, absent_rate = ?, result = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, row.totalSessions(), row.absentSessions(), newAbsentRate, row.result(), prev.id());
            }
        }

        if (!absenceIncreased) {
            return null;
        }
        insertWarningNotification(studentId, row, newAbsentRate);
        String email = findSingleId("SELECT email FROM users WHERE id = ?", studentId);
        return new AttendanceChangeNotification(
                studentId, email, row.studentCode(), row.courseCode(), row.semesterCode(),
                row.absentSessions(), row.totalSessions(), oldAbsentRate, newAbsentRate, row.result());
    }

    void scheduleAfterCommitNotifications(List<AttendanceChangeNotification> notifications) {
        Runnable task = () -> {
            for (AttendanceChangeNotification item : notifications) {
                dispatchAttendanceChangedEmail(item);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    String findSingleId(String sql, String param) {
        try {
            List<String> list = jdbc.query(sql, (rs, row) -> rs.getString(1), param);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ex) {
            log.debug("Lookup query returned no result for param {}: {}", param, ex.getMessage());
            return null;
        }
    }

    private void insertWarningNotification(String studentId, AttendanceImportRow row, BigDecimal newAbsentRate) {
        String title = "Attendance Alert: " + row.courseCode().toUpperCase(Locale.ROOT);
        String message = String.format(Locale.ROOT,
                "Your absence in %s (%s) changed to %d/%d total sessions (%s%% absent, status: %s).",
                row.courseCode().toUpperCase(Locale.ROOT),
                row.semesterCode().toUpperCase(Locale.ROOT),
                row.absentSessions(),
                row.totalSessions(),
                newAbsentRate.toPlainString(),
                row.result());
        try {
            jdbc.update("""
                    INSERT INTO notifications
                    (id, recipient_user_id, notification_type, title, message, related_student_id, is_read, created_at)
                    VALUES (?, ?, 'WARNING', ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
                    """, UUID.randomUUID().toString(), studentId, title, message, studentId);
        } catch (Exception ex) {
            log.debug("Skipping attendance notification insert for student {}: {}", studentId, ex.getMessage());
        }
    }

    private void dispatchAttendanceChangedEmail(AttendanceChangeNotification notification) {
        log.debug("Attendance alert ready for {} ({})", notification.studentCode(), notification.courseCode());
    }

    private record ExistingAttendance(String id, int absentSessions, BigDecimal absentRate) {
    }
}
