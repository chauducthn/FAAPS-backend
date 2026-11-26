package vn.edu.fpt.faaps.ai.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportPreview;
import vn.edu.fpt.faaps.academic.dto.TranscriptDtos.ImportRow;
import vn.edu.fpt.faaps.academic.service.TranscriptImportService;

/** Handles file/image attachments in Student AI Chat, including direct transcript import into the student's profile. */
final class StudentAiAttachmentHelper {
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Set<String> SPREADSHEET_EXT = Set.of(".xlsx", ".xls", ".csv");
    private static final Set<String> IMAGE_OR_PDF_EXT = Set.of(".png", ".jpg", ".jpeg", ".webp", ".pdf");

    private final TranscriptImportService transcriptImport;
    private final GeminiVisionClient vision;

    StudentAiAttachmentHelper(TranscriptImportService transcriptImport, GeminiVisionClient vision) {
        this.transcriptImport = transcriptImport;
        this.vision = vision;
    }

    AttachmentOutcome process(UUID studentId, String userText, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Attachment file is required");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("Attachment must be 10 MB or smaller");
        }
        String fileName = file.getOriginalFilename() == null ? "attachment" : file.getOriginalFilename().strip();
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String ext = extensionOf(lowerName);
        String lowerText = userText == null ? "" : userText.toLowerCase(Locale.ROOT);

        boolean wantsTranscriptUpdate = isTranscriptIntent(lowerText, lowerName, ext);
        if (wantsTranscriptUpdate && transcriptImport != null
                && (SPREADSHEET_EXT.contains(ext) || IMAGE_OR_PDF_EXT.contains(ext))) {
            return handleTranscriptUpload(studentId, fileName, ext, lowerText, file);
        }

        if (".txt".equals(ext) || ".csv".equals(ext) || ".md".equals(ext)) {
            return handleTextFile(fileName, file);
        }

        if (IMAGE_OR_PDF_EXT.contains(ext)) {
            return handleVisualFile(fileName, file);
        }

        return new AttachmentOutcome(
                fileName,
                "\n\n=== ATTACHED FILE ===\nFile: " + fileName + " (unsupported binary preview)\n",
                null,
                false);
    }

    private AttachmentOutcome handleTranscriptUpload(
            UUID studentId, String fileName, String ext, String lowerText, MultipartFile file) {
        try {
            ImportPreview preview = SPREADSHEET_EXT.contains(ext)
                    ? transcriptImport.preview(studentId, file)
                    : transcriptImport.previewOcr(studentId, file);

            if (preview.rowCount() == 0) {
                String msg = "Không tìm thấy dòng môn học hợp lệ nào trong file **" + fileName
                        + "**. Hãy kiểm tra lại định dạng bảng điểm FPTU (mã môn, học kỳ, điểm, trạng thái) hoặc tải lên tại mục Transcript & Import (`/student/transcript`).";
                return new AttachmentOutcome(fileName, "\n\n=== ATTACHMENT RESULT ===\n" + msg + "\n", msg, false);
            }

            if (preview.errorCount() > 0) {
                String sampleErrors = preview.rows().stream()
                        .filter(r -> r.error() != null)
                        .limit(5)
                        .map(r -> "- Dòng " + r.rowNumber() + " (" + safe(r.courseCode()) + "): " + r.error())
                        .collect(Collectors.joining("\n"));
                String msg = "Mình đã đọc file **" + fileName + "** (" + preview.rowCount() + " dòng), nhưng có **"
                        + preview.errorCount() + " dòng bị lỗi** nên chưa thể tự động cập nhật vào profile:\n"
                        + sampleErrors + "\n\nBạn có thể chỉnh sửa lại file hoặc vào **Transcript & Import** (`/student/transcript`) để kiểm tra chi tiết.";
                return new AttachmentOutcome(fileName, "\n\n=== ATTACHMENT RESULT ===\n" + msg + "\n", msg, false);
            }

            boolean shouldConfirm = wantsProfileSave(lowerText) || SPREADSHEET_EXT.contains(ext);
            if (shouldConfirm) {
                transcriptImport.confirm(studentId, UUID.fromString(preview.importId()));
                String coursesSummary = summarizeRows(preview.rows());
                String fallback = "Mình đã đọc file **" + fileName + "** và **cập nhật thành công "
                        + preview.rowCount() + " môn học** vào hồ sơ học tập (GPA và tín chỉ tích lũy đã được tính lại tự động).\n\n"
                        + coursesSummary
                        + "\n\nBạn có muốn mình phân tích điểm mạnh/yếu và gợi ý lộ trình học kỳ tới dựa trên bảng điểm mới này không?";
                String ctx = "\n\n=== ATTACHMENT ACTION COMPLETED ===\n"
                        + "Successfully imported and saved " + preview.rowCount() + " transcript rows from '" + fileName
                        + "' into the student's profile. GPA and credits were recalculated.\n"
                        + "Imported summary: " + coursesSummary + "\n"
                        + "Confirm to the student that their profile/transcript has been updated, then analyze their strengths, weaknesses, and recommended next steps.\n";
                return new AttachmentOutcome(fileName, ctx, fallback, true);
            }

            String summary = summarizeRows(preview.rows());
            String fallback = "Mình đã đọc thành công **" + preview.rowCount() + " môn học** từ file **" + fileName + "**:\n"
                    + summary + "\n\nNếu bạn muốn mình lưu vào profile, hãy nhắn *\"cập nhật bảng điểm này vào profile\"* kèm file nhé.";
            return new AttachmentOutcome(fileName, "\n\n=== ATTACHED TRANSCRIPT PREVIEW ===\n" + summary + "\n", fallback, false);
        } catch (Exception ex) {
            String reason = ex.getMessage() != null ? ex.getMessage() : "Không thể xử lý file bảng điểm";
            String fallback = "Mình chưa thể cập nhật bảng điểm từ file **" + fileName + "** (" + reason
                    + "). Bạn có thể thử lại với file Excel/CSV chuẩn FPTU hoặc tải lên trực tiếp ở trang **Transcript & Import** (`/student/transcript`).";
            return new AttachmentOutcome(fileName, "\n\n=== ATTACHMENT ERROR ===\n" + reason + "\n", fallback, false);
        }
    }

    private AttachmentOutcome handleTextFile(String fileName, MultipartFile file) {
        try {
            String raw = new String(file.getBytes(), StandardCharsets.UTF_8);
            String snippet = raw.length() > 6000 ? raw.substring(0, 6000) + "\n...(truncated)" : raw;
            return new AttachmentOutcome(
                    fileName,
                    "\n\n=== ATTACHED TEXT FILE: " + fileName + " ===\n" + snippet + "\n",
                    null,
                    false);
        } catch (Exception ex) {
            return new AttachmentOutcome(fileName, "\n\n=== ATTACHED FILE ===\nCould not read " + fileName + "\n", null, false);
        }
    }

    private AttachmentOutcome handleVisualFile(String fileName, MultipartFile file) {
        if (vision == null || !vision.isConfigured()) {
            String fallback = "Mình đã nhận được file **" + fileName
                    + "**, nhưng dịch vụ đọc ảnh/PDF (Vision OCR) hiện chưa được cấu hình API key trên server. "
                    + "Nếu đây là bảng điểm, bạn có thể xuất sang file **Excel (.xlsx) hoặc CSV** rồi gửi lại ở đây để mình cập nhật thẳng vào profile nhé!";
            return new AttachmentOutcome(fileName, "\n\n=== ATTACHED IMAGE/PDF (OCR UNAVAILABLE) ===\nFile: " + fileName + "\n", fallback, false);
        }
        try {
            String mime = file.getContentType() != null ? file.getContentType() : "image/png";
            String extracted = vision.extractText(
                    null,
                    "Extract all readable academic information (courses, grades, schedules, notes, or questions) from this student attachment concisely.",
                    file.getBytes(),
                    mime);
            return new AttachmentOutcome(
                    fileName,
                    "\n\n=== EXTRACTED CONTENT FROM ATTACHMENT (" + fileName + ") ===\n" + extracted + "\n",
                    null,
                    false);
        } catch (Exception ex) {
            String fallback = "Mình đã nhận được **" + fileName + "** nhưng tạm thời chưa đọc được nội dung ảnh/PDF ("
                    + (ex.getMessage() != null ? ex.getMessage() : "OCR error") + ").";
            return new AttachmentOutcome(fileName, "\n\n=== ATTACHMENT OCR ERROR ===\n" + fallback + "\n", fallback, false);
        }
    }

    private static boolean isTranscriptIntent(String lowerText, String lowerName, String ext) {
        if (SPREADSHEET_EXT.contains(ext)) {
            return true;
        }
        return lowerName.contains("transcript") || lowerName.contains("bang_diem") || lowerName.contains("bangdiem")
                || lowerText.contains("transcript") || lowerText.contains("bảng điểm") || lowerText.contains("bang diem")
                || wantsProfileSave(lowerText);
    }

    private static boolean wantsProfileSave(String lowerText) {
        return lowerText.contains("update") || lowerText.contains("cập nhật") || lowerText.contains("cap nhat")
                || lowerText.contains("import") || lowerText.contains("lưu") || lowerText.contains("profile")
                || lowerText.contains("hồ sơ") || lowerText.contains("ho so");
    }

    private static String summarizeRows(List<ImportRow> rows) {
        long passed = rows.stream().filter(r -> "PASSED".equalsIgnoreCase(r.status())).count();
        long failed = rows.stream().filter(r -> "FAILED".equalsIgnoreCase(r.status())).count();
        String sample = rows.stream().limit(8)
                .map(r -> r.courseCode() + (r.finalScore() != null ? " (" + r.finalScore() + ")" : ""))
                .collect(Collectors.joining(", "));
        return String.format("- Tổng: **%d môn** (Passed: %d, Failed: %d)%n- Một số môn: %s%s",
                rows.size(), passed, failed, sample, rows.size() > 8 ? ", …" : "");
    }

    private static String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx >= 0 ? fileName.substring(idx) : "";
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "?" : value;
    }

    record AttachmentOutcome(String fileName, String extraContext, String directFallbackReply, boolean profileUpdated) {}
}
