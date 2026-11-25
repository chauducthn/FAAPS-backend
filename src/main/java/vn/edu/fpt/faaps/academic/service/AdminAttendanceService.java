package vn.edu.fpt.faaps.academic.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminAttendanceService {
    public static final List<String> TEMPLATE_HEADERS = List.of(
            "Student ID", "Semester", "Course code", "Total sessions", "Absent sessions"
    );

    private final AttendanceConfigStore configStore;
    private final AttendanceImportProcessor importProcessor;

    public AdminAttendanceService(JdbcTemplate jdbc) {
        AttendanceSchemaInitializer schema = new AttendanceSchemaInitializer(jdbc);
        this.configStore = new AttendanceConfigStore(jdbc, schema);
        AttendanceDeltaUpdater deltaUpdater = new AttendanceDeltaUpdater(jdbc);
        this.importProcessor = new AttendanceImportProcessor(jdbc, schema, configStore, deltaUpdater);
    }

    public AttendanceRule getDefaultRule() {
        return configStore.getDefaultRule();
    }

    @Transactional
    public AttendanceRule saveDefaultRule(AttendanceRule rule) {
        return configStore.saveDefaultRule(rule);
    }

    public List<CourseAttendanceOverride> listOverrides() {
        return configStore.listOverrides();
    }

    @Transactional
    public CourseAttendanceOverride saveOverride(UUID courseId, AttendanceRule rule) {
        return configStore.saveOverride(courseId, rule);
    }

    @Transactional
    public void removeOverride(UUID courseId) {
        configStore.removeOverride(courseId);
    }

    @Transactional
    public AttendanceImportPreview previewImport(MultipartFile file) throws IOException {
        return importProcessor.previewImport(file);
    }

    @Transactional
    public AttendanceImportHistoryItem confirmImport(UUID importId) {
        return importProcessor.confirmImport(importId);
    }

    public List<AttendanceImportHistoryItem> listImports() {
        return importProcessor.listImports();
    }

    public static void validateRule(AttendanceRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Attendance rule is required.");
        }
        if (rule.totalSessions() < 1 || rule.totalSessions() > 200) {
            throw new IllegalArgumentException("Total sessions must be a whole number from 1 to 200.");
        }
        if (rule.minAttendancePercent() < 1 || rule.minAttendancePercent() > 100) {
            throw new IllegalArgumentException("Minimum attendance must be from 1% to 100%.");
        }
        if (rule.warnAbsentSessions() < 1) {
            throw new IllegalArgumentException("Warn at must be a whole number of at least 1.");
        }
        if (rule.warnAbsentSessions() > rule.totalSessions()) {
            throw new IllegalArgumentException("Warn at cannot be more than the total sessions.");
        }
    }

    public static int maxAbsentSessions(AttendanceRule rule) {
        return (int) Math.floor((rule.totalSessions() * (100.0 - rule.minAttendancePercent())) / 100.0);
    }

    public static String evaluateResult(AttendanceRule rule, int absentSessions) {
        if (absentSessions > maxAbsentSessions(rule)) {
            return "FAIL";
        }
        if (absentSessions >= rule.warnAbsentSessions()) {
            return "WARNING";
        }
        return "OK";
    }

    public record AttendanceRule(int totalSessions, int minAttendancePercent, int warnAbsentSessions) {
    }

    public record CourseAttendanceOverride(String courseId, String courseCode, String courseName,
            int totalSessions, int minAttendancePercent, int warnAbsentSessions, String updatedAt) {
    }

    public record AttendanceImportRow(int lineNumber, String studentCode, String semesterCode, String courseCode,
            Integer totalSessions, Integer absentSessions, String error, String result) {
    }

    public record AttendanceImportPreview(String importId, String fileName, List<AttendanceImportRow> rows,
            int validRows, int errorRows) {
    }

    public record AttendanceImportHistoryItem(String id, String fileName, int rowCount,
            int warningCount, int failCount, String confirmedAt) {
    }

    public record AttendanceChangeNotification(String studentId, String email, String studentCode,
            String courseCode, String semesterCode, int absentSessions, int totalSessions,
            BigDecimal oldAbsentRate, BigDecimal newAbsentRate, String result) {
    }
}
