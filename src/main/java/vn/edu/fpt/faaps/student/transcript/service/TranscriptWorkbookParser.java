package vn.edu.fpt.faaps.student.transcript.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.edu.fpt.faaps.student.transcript.dto.TranscriptDtos.ImportRow;

@Component
public class TranscriptWorkbookParser {
    private static final Set<String> STATUSES=Set.of("IN_PROGRESS","PASSED","FAILED","WITHDRAWN","EXEMPTED");
    private final JdbcTemplate jdbc;
    public TranscriptWorkbookParser(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<ImportRow> parse(MultipartFile file) {
        validateFile(file);
        List<ImportRow> rows=new ArrayList<>(); Set<String> keys=new HashSet<>(); DataFormatter formatter=new DataFormatter();
        try (XSSFWorkbook book=new XSSFWorkbook(file.getInputStream())) {
            if (book.getNumberOfSheets()==0) throw new IllegalArgumentException("Workbook has no sheet");
            var sheet=book.getSheetAt(0); Row header=sheet.getRow(0);
            if (header==null || !"CourseCode".equalsIgnoreCase(value(formatter,header,0))
                    || !"SemesterCode".equalsIgnoreCase(value(formatter,header,1))
                    || !"FinalScore".equalsIgnoreCase(value(formatter,header,2))
                    || !"Status".equalsIgnoreCase(value(formatter,header,3)))
                throw new IllegalArgumentException("Columns must start with CourseCode, SemesterCode, FinalScore, Status, AttemptNumber");
            if (sheet.getLastRowNum()>1000) throw new IllegalArgumentException("Maximum 1000 transcript rows per file");
            for (int index=1;index<=sheet.getLastRowNum();index++) {
                Row source=sheet.getRow(index); if(source==null) continue;
                String course=value(formatter,source,0),semester=value(formatter,source,1),scoreText=value(formatter,source,2);
                String status=value(formatter,source,3).toUpperCase(Locale.ROOT),attemptText=value(formatter,source,4);
                if(course.isBlank()&&semester.isBlank()&&scoreText.isBlank()&&status.isBlank()) continue;
                rows.add(validate(index+1,course,semester,scoreText,status,attemptText,keys));
            }
        } catch(IOException e) { throw new IllegalArgumentException("Unable to read the .xlsx file",e); }
        catch(RuntimeException e) { if(e instanceof IllegalArgumentException) throw e; throw new IllegalArgumentException("File is not a valid .xlsx workbook"); }
        if(rows.isEmpty()) throw new IllegalArgumentException("Workbook has no transcript rows");
        return rows;
    }

    private ImportRow validate(int line,String course,String semester,String scoreText,String status,String attemptText,Set<String> keys) {
        String error=null; BigDecimal score=null; int attempt=1;
        if(course.length()>50||semester.length()>50||status.length()>20) error="CourseCode, SemesterCode or Status is too long";
        try { if(!scoreText.isBlank()) score=new BigDecimal(scoreText); if(!attemptText.isBlank()) attempt=Integer.parseInt(attemptText);
            if(score!=null&&(score.signum()<0||score.compareTo(BigDecimal.TEN)>0)) error="FinalScore must be between 0 and 10";
            if(attempt<1) error="AttemptNumber must be positive";
        } catch(NumberFormatException e) { error="FinalScore or AttemptNumber is invalid"; }
        if(Set.of("PASSED","FAILED").contains(status)&&score==null) error="FinalScore is required for passed or failed courses";
        if(!STATUSES.contains(status)) error="Status is invalid";
        String courseId=idFor("SELECT id FROM courses WHERE code=?",course),semesterId=idFor("SELECT id FROM semesters WHERE code=?",semester);
        if(courseId==null) error="CourseCode not found"; if(semesterId==null) error="SemesterCode not found";
        if(!keys.add(course.toUpperCase()+"|"+semester.toUpperCase()+"|"+attempt)) error="Duplicate course, semester and attempt in this file";
        return new ImportRow(line,course,semester,score,status,attempt,courseId,semesterId,error);
    }

    private void validateFile(MultipartFile file) {
        if(file.isEmpty()||file.getSize()>5_000_000||file.getOriginalFilename()==null
                ||file.getOriginalFilename().length()>255||!file.getOriginalFilename().toLowerCase().endsWith(".xlsx"))
            throw new IllegalArgumentException("Choose an .xlsx file up to 5 MB");
    }
    private String idFor(String sql,String code) { return jdbc.query(sql,(rs,row)->rs.getString(1),code).stream().findFirst().orElse(null); }
    private String value(DataFormatter f,Row row,int column) { return row.getCell(column)==null?"":f.formatCellValue(row.getCell(column)).strip(); }
}
