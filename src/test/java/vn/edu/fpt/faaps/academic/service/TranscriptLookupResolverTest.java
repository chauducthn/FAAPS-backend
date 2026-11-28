package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** A FAP transcript code must find its FLM course even under another delivery variant. */
class TranscriptLookupResolverTest {
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:lookup-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE courses (id VARCHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(255), credits INT)");
        insert("IOT102", "Internet of Things", 3);
        insert("SWE201c", "Introduction to Software Engineering", 3);
        insert("ISM301", "ERP Systems", 3);
        insert("ISM301c", "International Strategic Management", 3);
        insert("PRC392m", "Cloud Computing", 3);
        insert("PRC392c", "Cloud Computing", 3);
    }

    @Test
    void aFapVariantFindsThePlainFlmCourse() {
        assertEquals("IOT102", code(TranscriptLookupResolver.resolveCourse(jdbc, "IOT102T")));
    }

    @Test
    void aPlainFapCodeFindsTheOnlyFlmVariant() {
        assertEquals("SWE201c", code(TranscriptLookupResolver.resolveCourse(jdbc, "SWE201")));
    }

    @Test
    void variantsOfOneSubjectResolve() {
        assertEquals("PRC392c", code(TranscriptLookupResolver.resolveCourse(jdbc, "PRC392")));
    }

    @Test
    void aFamilyOfUnrelatedCoursesStaysUnresolved() {
        assertTrue(TranscriptLookupResolver.resolveCourse(jdbc, "ISM301M").isEmpty());
    }

    @Test
    void anExactCodeAlwaysWins() {
        assertEquals("ISM301c", code(TranscriptLookupResolver.resolveCourse(jdbc, "ISM301C")));
    }

    private String code(java.util.List<Object[]> rows) {
        return rows.isEmpty() ? null : (String) rows.get(0)[1];
    }

    private void insert(String code, String name, int credits) {
        jdbc.update("INSERT INTO courses VALUES (?, ?, ?, ?)", UUID.randomUUID().toString(), code, name, credits);
    }
}
