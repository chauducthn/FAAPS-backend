package vn.edu.fpt.faaps.advising.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskQueryHelper.AdviseeRow;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskQueryHelper.AttendanceRiskSummary;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskService.AtRiskStudent;

class AdvisorAtRiskEvaluator {
    private static final BigDecimal GPA_WARNING_THRESHOLD = new BigDecimal("5.00");
    private static final BigDecimal GPA_HIGH_RISK_THRESHOLD = new BigDecimal("4.00");

    private final AdvisorAtRiskQueryHelper queries;

    AdvisorAtRiskEvaluator(AdvisorAtRiskQueryHelper queries) {
        this.queries = queries;
    }

    AtRiskStudent evaluate(AdviseeRow row) {
        List<String> reasons = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean highSeverity = false;

        BigDecimal gpa = row.currentGpa();
        boolean probationOrSuspended = "PROBATION".equalsIgnoreCase(row.academicStatus())
                || "SUSPENDED".equalsIgnoreCase(row.academicStatus());
        boolean hasGradedRecords = queries.countGradedCourses(row.id()) > 0;
        boolean hasLowGpaWarning = queries.hasUnresolvedWarning(row.id(), "LOW_GPA");

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

        AttendanceRiskSummary attendanceRisk = queries.checkAttendanceRisk(row.id());
        if (attendanceRisk.atRisk()) {
            reasons.add("HIGH_ABSENCE");
            details.add(attendanceRisk.detail());
            if (attendanceRisk.hasFail()) {
                highSeverity = true;
            }
        }

        int failedCount = queries.countUnrecoveredFailedCourses(row.id());
        boolean hasFailedCourseWarning = queries.hasUnresolvedWarning(row.id(), "FAILED_COURSES");
        if (failedCount > 0 || hasFailedCourseWarning) {
            reasons.add("FAILED_COURSES");
            details.add(failedCount > 0
                    ? (failedCount == 1 ? "1 failed course" : failedCount + " failed courses")
                    : "Failed course warning");
            if (failedCount >= 2) {
                highSeverity = true;
            }
        }

        if (reasons.isEmpty()) {
            return null;
        }

        String riskLevel = (highSeverity || reasons.size() >= 2) ? "HIGH" : "MEDIUM";
        String detectedAt = row.updatedAt() != null
                ? row.updatedAt().toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)
                : Instant.now().toString();

        return new AtRiskStudent(
                row.id(), row.studentCode(), row.fullName(), row.majorCode(), row.majorName(),
                row.currentSemester(), gpa != null ? gpa.doubleValue() : null, riskLevel,
                reasons, details.isEmpty() ? null : String.join(" · ", details),
                row.totalCreditsEarned(), detectedAt);
    }

    static int riskRank(String level) {
        return switch (level) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }
}
