package vn.edu.fpt.faaps.academic.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportPreview;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportRow;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceRule;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.CourseAttendanceOverride;

final class AttendanceImportPreviewHelper {
    private final JdbcTemplate jdbc;
    private final AttendanceSchemaInitializer schema;
    private final AttendanceConfigStore configStore;
    private final AttendanceDeltaUpdater deltaUpdater;

    AttendanceImportPreviewHelper(JdbcTemplate jdbc, AttendanceSchemaInitializer schema,
            AttendanceConfigStore configStore, AttendanceDeltaUpdater deltaUpdater) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.configStore = configStore;
        this.deltaUpdater = deltaUpdater;
    }

    AttendanceImportPreview previewImport(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        schema.ensureSchema();
        String fileName = file.getOriginalFilename() == null ? "attendance.csv" : file.getOriginalFilename();
        List<String[]> rawLines = AttendanceFileParser.readLines(fileName, file.getInputStream());
        if (rawLines.isEmpty()) {
            throw new IllegalArgumentException("The file has no data rows.");
        }
        AttendanceFileParser.validateHeaders(rawLines.get(0), AdminAttendanceService.TEMPLATE_HEADERS);

        AttendanceRule defaultRule = configStore.getDefaultRule();
        List<CourseAttendanceOverride> overrides = configStore.listOverrides();
        List<AttendanceImportRow> rows = parseAndValidateRows(rawLines, defaultRule, overrides);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no data rows.");
        }

        int errorRows = (int) rows.stream().filter(r -> r.error() != null).count();
        int validRows = rows.size() - errorRows;
        String importId = persistPreview(fileName, rows, validRows, errorRows);
        return new AttendanceImportPreview(importId, fileName, rows, validRows, errorRows);
    }

    private List<AttendanceImportRow> parseAndValidateRows(List<String[]> rawLines,
            AttendanceRule defaultRule, List<CourseAttendanceOverride> overrides) {
        Set<String> seen = new HashSet<>();
        List<AttendanceImportRow> rows = new ArrayList<>();
        for (int i = 1; i < rawLines.size(); i++) {
            String[] cells = rawLines.get(i);
            if (AttendanceFileParser.isBlankRow(cells)) {
                continue;
            }
            String studentCode = AttendanceFileParser.cellAt(cells, 0);
            String semesterCode = AttendanceFileParser.cellAt(cells, 1);
            String courseCode = AttendanceFileParser.cellAt(cells, 2);
            Integer totalSessions = AttendanceFileParser.parseCount(AttendanceFileParser.cellAt(cells, 3));
            Integer absentSessions = AttendanceFileParser.parseCount(AttendanceFileParser.cellAt(cells, 4));
            String key = (studentCode + "|" + semesterCode + "|" + courseCode).toUpperCase(Locale.ROOT);
            String error = validateRow(studentCode, semesterCode, courseCode, totalSessions, absentSessions, key, seen);
            seen.add(key);

            String result = null;
            if (error == null) {
                AttendanceRule rule = AttendanceConfigStore.ruleForCourse(courseCode, defaultRule, overrides);
                AttendanceRule effective = new AttendanceRule(
                        totalSessions, rule.minAttendancePercent(), rule.warnAbsentSessions());
                result = AdminAttendanceService.evaluateResult(effective, absentSessions);
            }
            rows.add(new AttendanceImportRow(
                    i + 1, studentCode, semesterCode, courseCode, totalSessions, absentSessions, error, result));
        }
        return rows;
    }

    private String validateRow(String studentCode, String semesterCode, String courseCode,
            Integer totalSessions, Integer absentSessions, String key, Set<String> seen) {
        if (studentCode.isEmpty() || semesterCode.isEmpty() || courseCode.isEmpty()) {
            return "Student ID, semester and course code are required.";
        }
        if (totalSessions == null || totalSessions < 1) {
            return "Total sessions must be a whole number of at least 1.";
        }
        if (absentSessions == null) {
            return "Absent sessions must be a whole number.";
        }
        if (absentSessions > totalSessions) {
            return "Absent sessions cannot be more than total sessions.";
        }
        if (seen.contains(key)) {
            return "Duplicate row for this student, semester and course.";
        }
        if (deltaUpdater.findSingleId("SELECT user_id FROM students WHERE UPPER(student_code) = ?",
                studentCode.toUpperCase(Locale.ROOT)) == null) {
            return "Student not found: " + studentCode;
        }
        if (deltaUpdater.findSingleId("SELECT id FROM semesters WHERE UPPER(code) = ?",
                semesterCode.toUpperCase(Locale.ROOT)) == null) {
            return "Semester not found: " + semesterCode;
        }
        if (deltaUpdater.findSingleId("SELECT id FROM courses WHERE UPPER(code) = ?",
                courseCode.toUpperCase(Locale.ROOT)) == null) {
            return "Course not found: " + courseCode;
        }
        return null;
    }

    private String persistPreview(String fileName, List<AttendanceImportRow> rows, int validRows, int errorRows) {
        String importId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO attendance_imports (id, file_name, status, row_count, valid_rows, error_rows, created_at)
                VALUES (?, ?, 'PREVIEW', ?, ?, ?, CURRENT_TIMESTAMP)
                """, importId, fileName, rows.size(), validRows, errorRows);
        for (AttendanceImportRow row : rows) {
            jdbc.update("""
                    INSERT INTO attendance_import_rows
                    (id, import_id, line_number, student_code, semester_code, course_code,
                     total_sessions, absent_sessions, validation_error, result)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), importId, row.lineNumber(), row.studentCode(),
                    row.semesterCode(), row.courseCode(), row.totalSessions(), row.absentSessions(),
                    row.error(), row.result());
        }
        return importId;
    }
}
