package vn.edu.fpt.faaps.academic.service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceChangeNotification;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportHistoryItem;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportPreview;
import vn.edu.fpt.faaps.academic.service.AdminAttendanceService.AttendanceImportRow;

final class AttendanceImportProcessor {
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final JdbcTemplate jdbc;
    private final AttendanceSchemaInitializer schema;
    private final AttendanceDeltaUpdater deltaUpdater;
    private final AttendanceImportPreviewHelper previewHelper;

    AttendanceImportProcessor(JdbcTemplate jdbc, AttendanceSchemaInitializer schema,
            AttendanceConfigStore configStore, AttendanceDeltaUpdater deltaUpdater) {
        this.jdbc = jdbc;
        this.schema = schema;
        this.deltaUpdater = deltaUpdater;
        this.previewHelper = new AttendanceImportPreviewHelper(jdbc, schema, configStore, deltaUpdater);
    }

    AttendanceImportPreview previewImport(MultipartFile file) throws IOException {
        return previewHelper.previewImport(file);
    }

    AttendanceImportHistoryItem confirmImport(UUID importId) {
        if (importId == null) {
            throw new IllegalArgumentException("importId is required");
        }
        schema.ensureSchema();
        ImportMeta meta = lockPreviewMeta(importId);
        List<AttendanceImportRow> validRows = loadRows(importId).stream().filter(r -> r.error() == null).toList();
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
            AttendanceChangeNotification change = deltaUpdater.applyAttendanceRowWithDeltaCheck(row);
            if (change != null) {
                pendingNotifications.add(change);
            }
        }

        LocalDateTime confirmedAt = LocalDateTime.now();
        jdbc.update("""
                UPDATE attendance_imports
                SET status = 'CONFIRMED', row_count = ?, warning_count = ?, fail_count = ?, confirmed_at = ?
                WHERE id = ?
                """, validRows.size(), warningCount, failCount, Timestamp.valueOf(confirmedAt), importId.toString());

        if (!pendingNotifications.isEmpty()) {
            deltaUpdater.scheduleAfterCommitNotifications(pendingNotifications);
        }
        return new AttendanceImportHistoryItem(importId.toString(), meta.fileName(),
                validRows.size(), warningCount, failCount, confirmedAt.format(ISO_DT));
    }

    List<AttendanceImportHistoryItem> listImports() {
        schema.ensureSchema();
        return jdbc.query("""
                SELECT id, file_name, row_count, warning_count, fail_count, confirmed_at
                FROM attendance_imports
                WHERE status = 'CONFIRMED'
                ORDER BY confirmed_at DESC, id DESC
                """, (rs, row) -> {
            Timestamp ts = rs.getTimestamp("confirmed_at");
            return new AttendanceImportHistoryItem(
                    rs.getString("id"), rs.getString("file_name"), rs.getInt("row_count"),
                    rs.getInt("warning_count"), rs.getInt("fail_count"),
                    ts != null ? ts.toLocalDateTime().format(ISO_DT) : LocalDateTime.now().format(ISO_DT));
        });
    }

    private ImportMeta lockPreviewMeta(UUID importId) {
        List<ImportMeta> metas = jdbc.query("""
                SELECT id, file_name, status, valid_rows FROM attendance_imports WHERE id = ? FOR UPDATE
                """, (rs, row) -> new ImportMeta(rs.getString("id"), rs.getString("file_name"),
                rs.getString("status"), rs.getInt("valid_rows")), importId.toString());
        if (metas.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "This preview has expired. Upload the file again.");
        }
        ImportMeta meta = metas.get(0);
        if (!"PREVIEW".equalsIgnoreCase(meta.status())) {
            throw new IllegalArgumentException("This attendance import has already been confirmed.");
        }
        return meta;
    }

    private List<AttendanceImportRow> loadRows(UUID importId) {
        return jdbc.query("""
                SELECT line_number, student_code, semester_code, course_code,
                       total_sessions, absent_sessions, validation_error, result
                FROM attendance_import_rows WHERE import_id = ? ORDER BY line_number ASC
                """, (rs, row) -> new AttendanceImportRow(rs.getInt("line_number"), rs.getString("student_code"),
                rs.getString("semester_code"), rs.getString("course_code"), (Integer) rs.getObject("total_sessions"),
                (Integer) rs.getObject("absent_sessions"), rs.getString("validation_error"),
                rs.getString("result")), importId.toString());
    }

    private record ImportMeta(String id, String fileName, String status, int validRows) {
    }
}
