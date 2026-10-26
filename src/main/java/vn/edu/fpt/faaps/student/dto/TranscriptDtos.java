package vn.edu.fpt.faaps.student.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class TranscriptDtos {
    private TranscriptDtos() {}
    public record ImportRow(int rowNumber,String courseCode,String semesterCode,BigDecimal finalScore,
            String status,int attemptNumber,String courseId,String semesterId,String error) {}
    public record ImportPreview(String importId,int rowCount,int errorCount,List<ImportRow> rows) {}
    public record ImportResult(String importId,int importedCount,String status) {}
    public record ImportHistory(String id,String fileName,String status,int rowCount,int errorCount,
            LocalDateTime createdAt,LocalDateTime confirmedAt) {}
}
