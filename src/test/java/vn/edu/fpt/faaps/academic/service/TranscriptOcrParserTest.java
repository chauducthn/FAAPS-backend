package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import vn.edu.fpt.faaps.ai.service.GeminiVisionClient;

class TranscriptOcrParserTest {
    private TranscriptOcrParser parser;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:ocr_parser_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE courses (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50))");
        jdbc.execute("CREATE TABLE semesters (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50))");
        jdbc.update("INSERT INTO courses(id, code) VALUES (?, 'PRF192')", UUID.randomUUID().toString());
        jdbc.update("INSERT INTO semesters(id, code) VALUES (?, 'FA2025')", UUID.randomUUID().toString());

        TranscriptWorkbookParser workbookParser = new TranscriptWorkbookParser(jdbc);
        parser = new TranscriptOcrParser(mock(GeminiVisionClient.class), workbookParser, jdbc);
    }

    @Test
    void parseJsonRowsStripsCodeFencesAndValidatesRows() {
        String raw = """
                ```json
                [{"courseCode":"prf192","semesterCode":"fa2025","finalScore":"8.5","status":"PASSED","attemptNumber":1}]
                ```
                """;
        var rows = parser.parseJsonRows(raw);
        assertEquals(1, rows.size());
        assertEquals("PRF192", rows.get(0).courseCode());
        assertEquals(new BigDecimal("8.5"), rows.get(0).finalScore());
        assertNull(rows.get(0).error());
    }

    @Test
    void parseRejectsUnsupportedMimeTypesAndInvalidJson() {
        MockMultipartFile textFile = new MockMultipartFile("file", "notes.txt", "text/plain", "hello".getBytes());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(textFile));
        assertThrows(IllegalArgumentException.class, () -> parser.parseJsonRows("not-json"));
        assertThrows(IllegalArgumentException.class, () -> parser.parseJsonRows("[]"));
    }
}
