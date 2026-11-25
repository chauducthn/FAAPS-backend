package vn.edu.fpt.faaps.advising.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdvisorAtRiskService {
    private static final BigDecimal GPA_WARNING_THRESHOLD = new BigDecimal("5.00");
    private static final BigDecimal GPA_HIGH_RISK_THRESHOLD = new BigDecimal("4.00");

    private final JdbcTemplate jdbc;

    public AdvisorAtRiskService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AtRiskStudentResponse list(UUID advisorId, String riskLevelFilter, String reasonFilter, int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Invalid page or size");
        }
        List<AtRiskStudent> allAtRisk = computeAllAtRisk(advisorId);

        List<AtRiskStudent> filtered = allAtRisk.stream()
                .filter(s -> riskLevelFilter == null || riskLevelFilter.isBlank()
                        || "ALL".equalsIgnoreCase(riskLevelFilter)
                        || s.riskLevel().equalsIgnoreCase(riskLevelFilter.strip()))
                .filter(s -> reasonFilter == null || reasonFilter.isBlank()
                        || "ALL".equalsIgnoreCase(reasonFilter)
                        || s.reasons().stream().anyMatch(r -> r.equalsIgnoreCase(reasonFilter.strip())))
                .toList();

        int total = filtered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<AtRiskStudent> pageItems = filtered.subList(fromIndex, toIndex);
        int totalPages = (total + size - 1) / size;

        return new AtRiskStudentResponse(pageItems, total, page, size, totalPages);
    }

    public AtRiskCounts counts(UUID advisorId) {
        List<AtRiskStudent> all = computeAllAtRisk(advisorId);
        int high = 0;
        int medium = 0;
        int low = 0;
        for (AtRiskStudent s : all) {
            switch (s.riskLevel()) {
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                default -> low++;
            }
        }
        return new AtRiskCounts(high, medium, low, all.size());
    }

    @Transactional
    public AtRiskStudent acknowledge(UUID advisorId, UUID studentId, String note) {
        List<AdviseeRow> rows = loadAdvisees(advisorId, studentId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        }
        try {
            jdbc.update("""
                    UPDATE academic_warnings
                    SET acknowledged_at = CURRENT_TIMESTAMP,
                        action_required = COALESCE(?, action_required),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE student_id = ? AND acknowledged_at IS NULL
                    """, note != null && !note.isBlank() ? note.strip() : null, studentId.toString());
        } catch (Exception ignored) {
        }
        AtRiskStudent evaluated = evaluateAdvisee(rows.get(0));
        if (evaluated != null) {
            return evaluated;
        }
        AdviseeRow r = rows.get(0);
        return new AtRiskStudent(
                r.id(),
                r.studentCode(),
                r.fullName(),
                r.majorCode(),
                r.majorName(),
                r.currentSemester(),
                r.currentGpa() != null ? r.currentGpa().doubleValue() : null,
                "LOW",
                List.of(),
                note != null && !note.isBlank() ? "Acknowledged: " + note.strip() : "Acknowledged",
                r.totalCreditsEarned(),
                Instant.now().toString()
        );
    }

    public List<AtRiskStudent> computeAllAtRisk(UUID advisorId) {
        List<AdviseeRow> advisees = loadAdvisees(advisorId, null);
        List<AtRiskStudent> result = new ArrayList<>();
        for (AdviseeRow row : advisees) {
            AtRiskStudent evaluated = evaluateAdvisee(row);
            if (evaluated != null) {
                result.add(evaluated);
            }
        }
        result.sort((a, b) -> {
            int rankDiff = riskRank(b.riskLevel()) - riskRank(a.riskLevel());
            if (rankDiff != 0) return rankDiff;
            double gpaA = a.currentGpa() != null ? a.currentGpa() : 10.0;
            double gpaB = b.currentGpa() != null ? b.currentGpa() : 10.0;
            return Double.compare(gpaA, gpaB);
        });
        return result;
    }

    private static int riskRank(String level) {
        return switch (level) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private List<AdviseeRow> loadAdvisees(UUID advisorId, UUID optionalStudentId) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.user_id, s.student_code, u.full_name, m.code AS major_code, m.name AS major_name,
                       s.current_semester, s.current_gpa,
                       COALESCE(s.total_credits_earned, 0) AS total_credits_earned,
                       s.academic_status, s.updated_at
                FROM student_advisor_assignments saa
                JOIN students s ON s.user_id = saa.student_id
                JOIN users u ON u.id = s.user_id
                LEFT JOIN majors m ON m.id = s.major_id
                WHERE saa.advisor_id = ? AND saa.status = 'ACTIVE' AND saa.end_date IS NULL
                """);
        List<Object> params = new ArrayList<>(List.of(advisorId.toString()));
        if (optionalStudentId != null) {
            sql.append(" AND s.user_id = ?");
            params.add(optionalStudentId.toString());
        }
        sql.append(" ORDER BY u.full_name ASC, s.student_code ASC");

        return jdbc.query(sql.toString(), (rs, row) -> new AdviseeRow(
                rs.getString("user_id"),
                rs.getString("student_code"),
                rs.getString("full_name"),
                rs.getString("major_code"),
                rs.getString("major_name"),
                (Integer) rs.getObject("current_semester"),
                rs.getBigDecimal("current_gpa"),
                rs.getInt("total_credits_earned"),
                rs.getString("academic_status"),
                rs.getTimestamp("updated_at")
        ), params.toArray());
    }

    private AtRiskStudent evaluateAdvisee(AdviseeRow row) {
        List<String> reasons = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean highSeverity = false;

        BigDecimal gpa = row.currentGpa();
        boolean probationOrSuspended = "PROBATION".equalsIgnoreCase(row.academicStatus())
                || "SUSPENDED".equalsIgnoreCase(row.academicStatus());
        boolean hasGradedRecords = countGradedCourses(row.id()) > 0;
        boolean hasLowGpaWarning = hasUnresolvedWarning(row.id(), "LOW_GPA");

        if ((gpa != null && gpa.compareTo(BigDecimal.ZERO) > 0 && gpa.compareTo(GPA_WARNING_THRESHOLD) < 0)
                || (gpa != null && gpa.compareTo(BigDecimal.ZERO) == 0 && hasGradedRecords)
                || probationOrSuspended
                || hasLowGpaWarning) {
            reasons.add("LOW_GPA");
            if (gpa != null && (gpa.compareTo(BigDecimal.ZERO) > 0 || hasGradedRecords)) {
                details.add(String.format(Locale.ROOT, "GPA %.2f < 5.00", gpa.doubleValue()));
                if (gpa.compareTo(GPA_HIGH_RISK_THRESHOLD) < 0) {
                    highSeverity = true;
                }
            } else if (probationOrSuspended) {
                details.add("Academic status: " + row.academicStatus());
            } else {
                details.add("Low GPA warning");
            }
            if (probationOrSuspended) {
                highSeverity = true;
            }
        }

        // Check attendance risk (student_course_attendance or academic_warnings)
        AttendanceRiskSummary attendanceRisk = checkAttendanceRisk(row.id());
        if (attendanceRisk.atRisk()) {
            reasons.add("HIGH_ABSENCE");
            details.add(attendanceRisk.detail());
            if (attendanceRisk.hasFail()) {
                highSeverity = true;
            }
        }

        // Check unrecovered failed courses
        int failedCount = countUnrecoveredFailedCourses(row.id());
        boolean hasFailedCourseWarning = hasUnresolvedWarning(row.id(), "FAILED_COURSES");
        if (failedCount > 0 || hasFailedCourseWarning) {
            reasons.add("FAILED_COURSES");
            if (failedCount > 0) {
                details.add(failedCount == 1 ? "1 failed course" : failedCount + " failed courses");
            } else {
                details.add("Failed course warning");
            }
            if (failedCount >= 2) {
                highSeverity = true;
            }
        }

        if (reasons.isEmpty()) {
            return null;
        }

        String riskLevel;
        if (highSeverity || reasons.size() >= 2) {
            riskLevel = "HIGH";
        } else {
            riskLevel = "MEDIUM";
        }

        String detectedAt = row.updatedAt() != null
                ? row.updatedAt().toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                : Instant.now().toString();

        return new AtRiskStudent(
                row.id(),
                row.studentCode(),
                row.fullName(),
                row.majorCode(),
                row.majorName(),
                row.currentSemester(),
                gpa != null ? gpa.doubleValue() : null,
                riskLevel,
                reasons,
                details.isEmpty() ? null : String.join(" · ", details),
                row.totalCreditsEarned(),
                detectedAt
        );
    }

    private int countGradedCourses(String studentId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM student_course_records
                    WHERE student_id = ? AND (final_score IS NOT NULL OR status IN ('PASSED', 'FAILED'))
                    """, Integer.class, studentId);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private int countUnrecoveredFailedCourses(String studentId) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(DISTINCT r.course_id)
                    FROM student_course_records r
                    WHERE r.student_id = ? AND r.status = 'FAILED'
                      AND NOT EXISTS (
                          SELECT 1 FROM student_course_records r2
                          WHERE r2.student_id = r.student_id
                            AND r2.course_id = r.course_id
                            AND r2.status IN ('PASSED', 'EXEMPTED')
                      )
                    """, Integer.class, studentId);
            return count == null ? 0 : count;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private AttendanceRiskSummary checkAttendanceRisk(String studentId) {
        try {
            List<String[]> rows = jdbc.query("""
                    SELECT absent_sessions, total_sessions, absent_rate, result
                    FROM student_course_attendance
                    WHERE student_id = ? AND result IN ('WARNING', 'FAIL')
                    ORDER BY absent_rate DESC
                    """, (rs, r) -> new String[] {
                    String.valueOf(rs.getInt("absent_sessions")),
                    String.valueOf(rs.getInt("total_sessions")),
                    rs.getBigDecimal("absent_rate") != null ? rs.getBigDecimal("absent_rate").toPlainString() : "0",
                    rs.getString("result")
            }, studentId);
            if (!rows.isEmpty()) {
                boolean hasFail = rows.stream().anyMatch(r -> "FAIL".equalsIgnoreCase(r[3]));
                String[] worst = rows.get(0);
                String detail = "Absent " + worst[0] + "/" + worst[1] + " sessions (" + worst[2] + "%)";
                return new AttendanceRiskSummary(true, hasFail, detail);
            }
        } catch (Exception ignored) {
        }
        if (hasUnresolvedWarning(studentId, "ATTENDANCE")) {
            return new AttendanceRiskSummary(true, false, "High absence warning");
        }
        return new AttendanceRiskSummary(false, false, null);
    }

    private boolean hasUnresolvedWarning(String studentId, String warningType) {
        try {
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM academic_warnings
                    WHERE student_id = ? AND warning_type = ?
                      AND resolved_at IS NULL AND acknowledged_at IS NULL
                    """, Integer.class, studentId, warningType);
            return count != null && count > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private record AdviseeRow(String id, String studentCode, String fullName, String majorCode,
            String majorName, Integer currentSemester, BigDecimal currentGpa, int totalCreditsEarned,
            String academicStatus, Timestamp updatedAt) {}
    private record AttendanceRiskSummary(boolean atRisk, boolean hasFail, String detail) {}

    public record AtRiskStudent(
            String id,
            String studentCode,
            String fullName,
            String majorCode,
            String majorName,
            Integer currentSemester,
            Double currentGpa,
            String riskLevel,
            List<String> reasons,
            String reasonDetails,
            int totalCreditsEarned,
            String detectedAt
    ) {}

    public record AtRiskStudentResponse(
            List<AtRiskStudent> data,
            int total,
            int page,
            int size,
            int totalPages
    ) {}

    public record AtRiskCounts(int high, int medium, int low, int total) {}
}
