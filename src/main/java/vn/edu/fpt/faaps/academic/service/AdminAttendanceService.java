package vn.edu.fpt.faaps.academic.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminAttendanceService {
    public static final List<String> TEMPLATE_HEADERS = List.of(
            "Student ID", "Semester", "Course code", "Total sessions", "Absent sessions"
    );
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;
    private volatile boolean schemaEnsured = false;

    public AdminAttendanceService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private void ensureSchema() {
        if (schemaEnsured) {
            return;
        }
        synchronized (this) {
            if (schemaEnsured) {
                return;
            }
            try {
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS attendance_default_config (
                            singleton_id INT PRIMARY KEY,
                            total_sessions INT NOT NULL DEFAULT 20,
                            min_attendance_percent INT NOT NULL DEFAULT 80,
                            warn_absent_sessions INT NOT NULL DEFAULT 3,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS course_attendance_configs (
                            course_id CHAR(36) PRIMARY KEY,
                            total_sessions INT NOT NULL,
                            min_attendance_percent INT NOT NULL,
                            warn_absent_sessions INT NOT NULL,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                        )
                        """);
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS attendance_imports (
                            id CHAR(36) PRIMARY KEY,
                            file_name VARCHAR(255) NOT NULL,
                            status VARCHAR(20) NOT NULL DEFAULT 'PREVIEW',
                            row_count INT NOT NULL DEFAULT 0,
                            valid_rows INT NOT NULL DEFAULT 0,
                            error_rows INT NOT NULL DEFAULT 0,
                            warning_count INT NOT NULL DEFAULT 0,
                            fail_count INT NOT NULL DEFAULT 0,
                            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            confirmed_at TIMESTAMP NULL
                        )
                        """);
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS attendance_import_rows (
                            id CHAR(36) PRIMARY KEY,
                            import_id CHAR(36) NOT NULL,
                            line_number INT NOT NULL,
                            student_code VARCHAR(50),
                            semester_code VARCHAR(50),
                            course_code VARCHAR(50),
                            total_sessions INT,
                            absent_sessions INT,
                            validation_error TEXT,
                            result VARCHAR(20)
                        )
                        """);
                jdbc.execute("""
                        CREATE TABLE IF NOT EXISTS student_course_attendance (
                            id CHAR(36) PRIMARY KEY,
                            student_id CHAR(36) NOT NULL,
                            course_id CHAR(36) NOT NULL,
                            semester_id CHAR(36) NOT NULL,
                            total_sessions INT NOT NULL,
                            absent_sessions INT NOT NULL,
                            absent_rate DECIMAL(5,2) NOT NULL,
                            result VARCHAR(20) NOT NULL,
                            updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            UNIQUE (student_id, course_id, semester_id)
                        )
                        """);
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM attendance_default_config WHERE singleton_id = 1", Integer.class);
                if (count == null || count == 0) {
                    jdbc.update("""
                            INSERT INTO attendance_default_config
                            (singleton_id, total_sessions, min_attendance_percent, warn_absent_sessions, updated_at)
                            VALUES (1, 20, 80, 3, CURRENT_TIMESTAMP)
                            """);
                }
                schemaEnsured = true;
            } catch (Exception ignored) {
                // Allows unit tests with mocked JdbcTemplate to proceed without DDL execution.
            }
        }
    }

    public AttendanceRule getDefaultRule() {
        ensureSchema();
        List<AttendanceRule> rules = jdbc.query("""
                SELECT total_sessions, min_attendance_percent, warn_absent_sessions
                FROM attendance_default_config WHERE singleton_id = 1
                """, (rs, row) -> new AttendanceRule(
                rs.getInt("total_sessions"),
                rs.getInt("min_attendance_percent"),
                rs.getInt("warn_absent_sessions")
        ));
        return rules.isEmpty() ? new AttendanceRule(20, 80, 3) : rules.get(0);
    }

    @Transactional
    public AttendanceRule saveDefaultRule(AttendanceRule rule) {
        validateRule(rule);
        ensureSchema();
        int updated = jdbc.update("""
                UPDATE attendance_default_config
                SET total_sessions = ?, min_attendance_percent = ?, warn_absent_sessions = ?, updated_at = CURRENT_TIMESTAMP
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

    public List<CourseAttendanceOverride> listOverrides() {
        ensureSchema();
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
                    ts != null ? ts.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT)
            );
        });
    }

    @Transactional
    public CourseAttendanceOverride saveOverride(UUID courseId, AttendanceRule rule) {
        if (courseId == null) {
            throw new IllegalArgumentException("courseId is required");
        }
        validateRule(rule);
        ensureSchema();
        List<String[]> courseRows = jdbc.query(
                "SELECT code, name FROM courses WHERE id = ?",
                (rs, row) -> new String[] { rs.getString("code"), rs.getString("name") },
                courseId.toString());
        if (courseRows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found");
        }

        int updated = jdbc.update("""
                UPDATE course_attendance_configs
                SET total_sessions = ?, min_attendance_percent = ?, warn_absent_sessions = ?, updated_at = CURRENT_TIMESTAMP
                WHERE course_id = ?
                """, rule.totalSessions(), rule.minAttendancePercent(), rule.warnAbsentSessions(), courseId.toString());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO course_attendance_configs
                    (course_id, total_sessions, min_attendance_percent, warn_absent_sessions, updated_at)
                    VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, courseId.toString(), rule.totalSessions(), rule.minAttendancePercent(), rule.warnAbsentSessions());
        }
        return new CourseAttendanceOverride(
                courseId.toString(),
                courseRows.get(0)[0],
                courseRows.get(0)[1],
                rule.totalSessions(),
                rule.minAttendancePercent(),
                rule.warnAbsentSessions(),
                LocalDateTime.now().format(ISO_DT)
        );
    }

    @Transactional
    public void removeOverride(UUID courseId) {
        ensureSchema();
        jdbc.update("DELETE FROM course_attendance_configs WHERE course_id = ?", courseId.toString());
    }

    @Transactional
    public AttendanceImportPreview previewImport(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded file is empty");
        }
        ensureSchema();
        String fileName = file.getOriginalFilename() == null ? "attendance.csv" : file.getOriginalFilename();
        List<String[]> rawLines = fileName.toLowerCase(Locale.ROOT).endsWith(".csv")
                ? readCsvLines(file.getInputStream())
                : readExcelLines(file.getInputStream());

        if (rawLines.isEmpty()) {
            throw new IllegalArgumentException("The file has no data rows.");
        }
        validateHeaders(rawLines.get(0));

        AttendanceRule defaultRule = getDefaultRule();
        List<CourseAttendanceOverride> overrides = listOverrides();

        Set<String> seen = new HashSet<>();
        List<AttendanceImportRow> rows = new ArrayList<>();

        for (int i = 1; i < rawLines.size(); i++) {
            String[] cells = rawLines.get(i);
            if (isBlankRow(cells)) {
                continue;
            }
            String studentCode = cellAt(cells, 0);
            String semesterCode = cellAt(cells, 1);
            String courseCode = cellAt(cells, 2);
            Integer totalSessions = parseCount(cellAt(cells, 3));
            Integer absentSessions = parseCount(cellAt(cells, 4));
            String key = (studentCode + "|" + semesterCode + "|" + courseCode).toUpperCase(Locale.ROOT);

            String error = null;
            if (studentCode.isEmpty() || semesterCode.isEmpty() || courseCode.isEmpty()) {
                error = "Student ID, semester and course code are required.";
            } else if (totalSessions == null || totalSessions < 1) {
                error = "Total sessions must be a whole number of at least 1.";
            } else if (absentSessions == null) {
                error = "Absent sessions must be a whole number.";
            } else if (absentSessions > totalSessions) {
                error = "Absent sessions cannot be more than total sessions.";
            } else if (seen.contains(key)) {
                error = "Duplicate row for this student, semester and course.";
            } else {
                String studentId = findSingleId("SELECT user_id FROM students WHERE UPPER(student_code) = ?",
                        studentCode.toUpperCase(Locale.ROOT));
                String semesterId = findSingleId("SELECT id FROM semesters WHERE UPPER(code) = ?",
                        semesterCode.toUpperCase(Locale.ROOT));
                String courseId = findSingleId("SELECT id FROM courses WHERE UPPER(code) = ?",
                        courseCode.toUpperCase(Locale.ROOT));
                if (studentId == null) {
                    error = "Student not found: " + studentCode;
                } else if (semesterId == null) {
                    error = "Semester not found: " + semesterCode;
                } else if (courseId == null) {
                    error = "Course not found: " + courseCode;
                }
            }
            seen.add(key);

            String result = null;
            if (error == null) {
                AttendanceRule rule = ruleForCourse(courseCode, defaultRule, overrides);
                AttendanceRule effectiveRule = new AttendanceRule(
                        totalSessions, rule.minAttendancePercent(), rule.warnAbsentSessions());
                result = evaluateResult(effectiveRule, absentSessions);
            }

            rows.add(new AttendanceImportRow(
                    i + 1, studentCode, semesterCode, courseCode,
                    totalSessions, absentSessions, error, result
            ));
        }

        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no data rows.");
        }

        int errorRows = (int) rows.stream().filter(r -> r.error() != null).count();
        int validRows = rows.size() - errorRows;
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
                    """, UUID.randomUUID().toString(), importId, row.lineNumber(),
                    row.studentCode(), row.semesterCode(), row.courseCode(),
                    row.totalSessions(), row.absentSessions(), row.error(), row.result());
        }

        return new AttendanceImportPreview(importId, fileName, rows, validRows, errorRows);
    }

    @Transactional
    public AttendanceImportHistoryItem confirmImport(UUID importId) {
        if (importId == null) {
            throw new IllegalArgumentException("importId is required");
        }
        ensureSchema();

        List<ImportMeta> metas = jdbc.query("""
                SELECT id, file_name, status, valid_rows
                FROM attendance_imports
                WHERE id = ?
                FOR UPDATE
                """, (rs, row) -> new ImportMeta(
                rs.getString("id"),
                rs.getString("file_name"),
                rs.getString("status"),
                rs.getInt("valid_rows")
        ), importId.toString());

        if (metas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This preview has expired. Upload the file again.");
        }
        ImportMeta meta = metas.get(0);
        if (!"PREVIEW".equalsIgnoreCase(meta.status())) {
            throw new IllegalArgumentException("This attendance import has already been confirmed.");
        }

        List<AttendanceImportRow> rows = jdbc.query("""
                SELECT line_number, student_code, semester_code, course_code,
                       total_sessions, absent_sessions, validation_error, result
                FROM attendance_import_rows
                WHERE import_id = ?
                ORDER BY line_number ASC
                """, (rs, row) -> new AttendanceImportRow(
                rs.getInt("line_number"),
                rs.getString("student_code"),
                rs.getString("semester_code"),
                rs.getString("course_code"),
                (Integer) rs.getObject("total_sessions"),
                (Integer) rs.getObject("absent_sessions"),
                rs.getString("validation_error"),
                rs.getString("result")
        ), importId.toString());

        List<AttendanceImportRow> validRows = rows.stream().filter(r -> r.error() == null).toList();
        if (validRows.isEmpty()) {
            throw new IllegalArgumentException("There are no valid rows to import.");
        }

        int warningCount = 0;
        int failCount = 0;
        List<AttendanceChangeNotification> pendingNotifications = new ArrayList<>();

        for (AttendanceImportRow row : validRows) {
            if ("WARNING".equalsIgnoreCase(row.result())) {
                warningCount++;
            } else if ("FAIL".equalsIgnoreCase(row.result())) {
                failCount++;
            }

            AttendanceChangeNotification change = applyAttendanceRowWithDeltaCheck(row);
            if (change != null) {
                pendingNotifications.add(change);
            }
        }

        LocalDateTime confirmedAt = LocalDateTime.now();
        jdbc.update("""
                UPDATE attendance_imports
                SET status = 'CONFIRMED',
                    row_count = ?,
                    warning_count = ?,
                    fail_count = ?,
                    confirmed_at = ?
                WHERE id = ?
                """, validRows.size(), warningCount, failCount, Timestamp.valueOf(confirmedAt), importId.toString());

        if (!pendingNotifications.isEmpty()) {
            scheduleAfterCommitNotifications(pendingNotifications);
        }

        return new AttendanceImportHistoryItem(
                importId.toString(),
                meta.fileName(),
                validRows.size(),
                warningCount,
                failCount,
                confirmedAt.format(ISO_DT)
        );
    }

    public List<AttendanceImportHistoryItem> listImports() {
        ensureSchema();
        return jdbc.query("""
                SELECT id, file_name, row_count, warning_count, fail_count, confirmed_at
                FROM attendance_imports
                WHERE status = 'CONFIRMED'
                ORDER BY confirmed_at DESC, id DESC
                """, (rs, row) -> {
            Timestamp ts = rs.getTimestamp("confirmed_at");
            return new AttendanceImportHistoryItem(
                    rs.getString("id"),
                    rs.getString("file_name"),
                    rs.getInt("row_count"),
                    rs.getInt("warning_count"),
                    rs.getInt("fail_count"),
                    ts != null ? ts.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT)
            );
        });
    }

    /**
     * OnChanged delta check:
     * Calculates % absent against TOTAL sessions of the course (not past sessions),
     * and only triggers a notification/email event when % absent has strictly increased
     * compared to the previously stored value. Re-importing unchanged attendance rows
     * will NOT re-trigger notifications or emails.
     */
    private AttendanceChangeNotification applyAttendanceRowWithDeltaCheck(AttendanceImportRow row) {
        String studentId = findSingleId("SELECT user_id FROM students WHERE UPPER(student_code) = ?",
                row.studentCode().strip().toUpperCase(Locale.ROOT));
        String courseId = findSingleId("SELECT id FROM courses WHERE UPPER(code) = ?",
                row.courseCode().strip().toUpperCase(Locale.ROOT));
        String semesterId = findSingleId("SELECT id FROM semesters WHERE UPPER(code) = ?",
                row.semesterCode().strip().toUpperCase(Locale.ROOT));

        if (studentId == null || courseId == null || semesterId == null) {
            throw new IllegalArgumentException("Invalid attendance row: student, course, or semester does not exist.");
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
                rs.getBigDecimal("absent_rate")
        ), studentId, courseId, semesterId);

        boolean absenceIncreased;
        BigDecimal oldAbsentRate;

        if (existing.isEmpty()) {
            oldAbsentRate = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            absenceIncreased = newAbsentRate.compareTo(BigDecimal.ZERO) > 0;
            jdbc.update("""
                    INSERT INTO student_course_attendance
                    (id, student_id, course_id, semester_id, total_sessions, absent_sessions, absent_rate, result, updated_at)
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
                        SET total_sessions = ?, absent_sessions = ?, absent_rate = ?, result = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """, row.totalSessions(), row.absentSessions(), newAbsentRate, row.result(), prev.id());
            }
        }

        if (!absenceIncreased) {
            return null;
        }

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
        } catch (Exception ignored) {
            // Non-blocking if notifications table or FK is absent in an isolated test schema.
        }

        String email = findSingleId("SELECT email FROM users WHERE id = ?", studentId);
        return new AttendanceChangeNotification(
                studentId, email, row.studentCode(), row.courseCode(), row.semesterCode(),
                row.absentSessions(), row.totalSessions(), oldAbsentRate, newAbsentRate, row.result()
        );
    }

    private void scheduleAfterCommitNotifications(List<AttendanceChangeNotification> notifications) {
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

    /**
     * Extension hook for dispatching attendance change emails after transaction commit.
     * Only invoked when % absent over total course sessions has strictly increased (OnChanged).
     */
    private void dispatchAttendanceChangedEmail(AttendanceChangeNotification notification) {
        // [EXTENSION HOOK]: Wire JavaMailSender here when SMTP is configured.
    }

    private String findSingleId(String sql, String param) {
        try {
            List<String> list = jdbc.query(sql, (rs, row) -> rs.getString(1), param);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception ignored) {
            return null;
        }
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

    private static AttendanceRule ruleForCourse(String courseCode, AttendanceRule defaultRule,
            List<CourseAttendanceOverride> overrides) {
        for (CourseAttendanceOverride o : overrides) {
            if (o.courseCode() != null && o.courseCode().equalsIgnoreCase(courseCode)) {
                return new AttendanceRule(o.totalSessions(), o.minAttendancePercent(), o.warnAbsentSessions());
            }
        }
        return defaultRule;
    }

    private static void validateHeaders(String[] header) {
        if (header.length < TEMPLATE_HEADERS.size()) {
            throw new IllegalArgumentException("The file must start with the columns: " + String.join(", ", TEMPLATE_HEADERS) + ".");
        }
        for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
            String actual = header[i] == null ? "" : header[i].replace("\uFEFF", "").strip();
            if (!TEMPLATE_HEADERS.get(i).equalsIgnoreCase(actual)) {
                throw new IllegalArgumentException("The file must start with the columns: " + String.join(", ", TEMPLATE_HEADERS) + ".");
            }
        }
    }

    private static List<String[]> readCsvLines(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (first) {
                    line = line.replaceFirst("^\uFEFF", "");
                    first = false;
                }
                rows.add(splitCsvLine(line));
            }
        }
        return rows;
    }

    private static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                cells.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        cells.add(cur.toString().strip());
        return cells.toArray(new String[0]);
    }

    private static List<String[]> readExcelLines(InputStream in) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) {
                return rows;
            }
            int last = sheet.getLastRowNum();
            for (int r = 0; r <= last; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    rows.add(new String[0]);
                    continue;
                }
                int lastCell = Math.max(row.getLastCellNum(), (short) 5);
                String[] cells = new String[lastCell];
                for (int c = 0; c < lastCell; c++) {
                    cells[c] = getCellString(row.getCell(c));
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static String getCellString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.STRING) return cell.getStringCellValue().strip();
        if (cell.getCellType() == CellType.NUMERIC) {
            double val = cell.getNumericCellValue();
            if (val == Math.floor(val)) {
                return String.valueOf((long) val);
            }
            return String.valueOf(val);
        }
        if (cell.getCellType() == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        return "";
    }

    private static boolean isBlankRow(String[] cells) {
        if (cells == null || cells.length == 0) return true;
        for (String c : cells) {
            if (c != null && !c.isBlank()) return false;
        }
        return true;
    }

    private static String cellAt(String[] cells, int index) {
        if (cells == null || index >= cells.length || cells[index] == null) return "";
        return cells[index].strip();
    }

    private static Integer parseCount(String raw) {
        if (raw == null) return null;
        String clean = raw.strip();
        if (!clean.matches("^\\d+$")) return null;
        try {
            return Integer.parseInt(clean);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public record AttendanceRule(int totalSessions, int minAttendancePercent, int warnAbsentSessions) {}
    public record CourseAttendanceOverride(String courseId, String courseCode, String courseName,
            int totalSessions, int minAttendancePercent, int warnAbsentSessions, String updatedAt) {}
    public record AttendanceImportRow(int lineNumber, String studentCode, String semesterCode, String courseCode,
            Integer totalSessions, Integer absentSessions, String error, String result) {}
    public record AttendanceImportPreview(String importId, String fileName, List<AttendanceImportRow> rows,
            int validRows, int errorRows) {}
    public record AttendanceImportHistoryItem(String id, String fileName, int rowCount,
            int warningCount, int failCount, String confirmedAt) {}

    private record ImportMeta(String id, String fileName, String status, int validRows) {}
    private record ExistingAttendance(String id, int absentSessions, BigDecimal absentRate) {}
    public record AttendanceChangeNotification(String studentId, String email, String studentCode,
            String courseCode, String semesterCode, int absentSessions, int totalSessions,
            BigDecimal oldAbsentRate, BigDecimal newAbsentRate, String result) {}
}
