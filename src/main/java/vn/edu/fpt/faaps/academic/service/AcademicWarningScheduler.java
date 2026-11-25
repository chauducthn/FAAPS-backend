package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.service.AcademicWarningRepository.AttendanceSnap;
import vn.edu.fpt.faaps.academic.service.AcademicWarningRepository.StudentSnapshot;

@Service
public class AcademicWarningScheduler {
    private static final Logger log = LoggerFactory.getLogger(AcademicWarningScheduler.class);
    private static final BigDecimal GPA_WARNING_THRESHOLD = new BigDecimal("5.00");
    private static final BigDecimal GPA_PROBATION_THRESHOLD = new BigDecimal("4.00");

    private final AcademicWarningRepository repository;

    public AcademicWarningScheduler(JdbcTemplate jdbc) {
        this.repository = new AcademicWarningRepository(jdbc);
    }

    /**
     * Automatic daily cron job (02:00 AM by default) to evaluate all active students
     * and issue academic warnings for low GPA (< 5.00), unrecovered failed courses, or high absence.
     */
    @Scheduled(cron = "${faaps.academic.warning-cron:0 0 2 * * *}")
    public void runDailyWarningCheck() {
        try {
            syncAllStudentWarnings();
        } catch (Exception ex) {
            log.debug("Daily academic warning check skipped: {}", ex.getMessage());
        }
    }

    @Transactional
    public int syncAllStudentWarnings() {
        List<String> studentIds = repository.findActiveStudentIds();
        int generated = 0;
        for (String id : studentIds) {
            try {
                generated += syncStudentWarnings(UUID.fromString(id));
            } catch (Exception ex) {
                log.warn("Failed to sync academic warnings for student {}: {}", id, ex.getMessage());
            }
        }
        return generated;
    }

    @Transactional
    public int syncStudentWarnings(UUID studentId) {
        String id = studentId.toString();
        String semesterId = repository.resolveCurrentSemesterId();
        String issuerId = repository.resolveSystemIssuerId(id);
        if (semesterId == null || issuerId == null) {
            return 0;
        }
        StudentSnapshot snap = repository.findStudentSnapshot(id);
        if (snap == null) {
            return 0;
        }
        int created = 0;
        created += checkLowGpa(id, semesterId, issuerId, snap.currentGpa());
        created += checkFailedCourses(id, semesterId, issuerId);
        created += checkAttendance(id, semesterId, issuerId);
        return created;
    }

    private int checkLowGpa(String studentId, String semesterId, String issuerId, BigDecimal gpa) {
        boolean hasGraded = repository.countGradedCourses(studentId) > 0;
        boolean lowGpa = (gpa != null && gpa.compareTo(BigDecimal.ZERO) > 0
                && gpa.compareTo(GPA_WARNING_THRESHOLD) < 0)
                || (gpa != null && gpa.compareTo(BigDecimal.ZERO) == 0 && hasGraded);
        if (!lowGpa) {
            return 0;
        }
        String severity = gpa.compareTo(GPA_PROBATION_THRESHOLD) < 0 ? "PROBATION" : "WARNING";
        String desc = String.format(Locale.ROOT,
                "Cumulative GPA is %.2f (below minimum threshold of 5.00).", gpa.doubleValue());
        String action = "Meet with your academic advisor to adjust your study plan and retake low-grade courses.";
        return repository.ensureWarning(studentId, semesterId, "LOW_GPA", severity, desc, action, issuerId) ? 1 : 0;
    }

    private int checkFailedCourses(String studentId, String semesterId, String issuerId) {
        int failedCount = repository.countUnrecoveredFailedCourses(studentId);
        if (failedCount <= 0) {
            return 0;
        }
        String severity = failedCount >= 3 ? "FINAL_WARNING" : (failedCount == 2 ? "PROBATION" : "WARNING");
        String desc = "You have " + failedCount + " unrecovered failed course(s) on your transcript.";
        String action = "Prioritize registering for retake classes in the upcoming semester.";
        return repository.ensureWarning(studentId, semesterId, "FAILED_COURSES", severity, desc, action, issuerId)
                ? 1 : 0;
    }

    private int checkAttendance(String studentId, String semesterId, String issuerId) {
        AttendanceSnap att = repository.worstAttendanceRisk(studentId);
        if (att == null) {
            return 0;
        }
        String severity = "FAIL".equalsIgnoreCase(att.result()) ? "PROBATION" : "WARNING";
        String desc = String.format(Locale.ROOT,
                "High absence recorded: %d/%d total sessions (%s%% absent, status: %s).",
                att.absentSessions(), att.totalSessions(), att.absentRate(), att.result());
        String action = "Attend all remaining class sessions to avoid failing the attendance condition.";
        return repository.ensureWarning(studentId, semesterId, "ATTENDANCE", severity, desc, action, issuerId) ? 1 : 0;
    }
}
