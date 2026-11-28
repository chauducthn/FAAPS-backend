package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.CohortRow;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.CurriculumLinkRow;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.ImportRequest;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.ImportSummary;
import vn.edu.fpt.faaps.academic.service.AdminStructureImportService.MajorRow;

/**
 * The structure import must accept two majors that share a code but have
 * different names (FLM lists more than one programme under a code). It used to
 * fail with "Duplicate entry 'LLB' for key 'majors.code'" and return a 500.
 */
class AdminStructureImportServiceTest {

    private JdbcTemplate jdbc;
    private AdminStructureImportService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:structure-import-" + java.util.UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE majors (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) NOT NULL, name VARCHAR(255) NOT NULL,
                    description TEXT, total_credits INT NOT NULL, duration_years DECIMAL(3,1) NOT NULL,
                    status VARCHAR(20) DEFAULT 'ACTIVE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uq_majors_code_name (code, name)
                )
                """);
        jdbc.execute("""
                CREATE TABLE semesters (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL,
                    start_date DATE NOT NULL, end_date DATE NOT NULL, is_current BOOLEAN DEFAULT FALSE,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_versions (
                    id VARCHAR(36) PRIMARY KEY, major_id VARCHAR(36) NOT NULL, version VARCHAR(50) NOT NULL,
                    name VARCHAR(255) NOT NULL, effective_from_semester_id VARCHAR(36),
                    effective_to_semester_id VARCHAR(36), is_current BOOLEAN DEFAULT TRUE,
                    status VARCHAR(20) DEFAULT 'ACTIVE', created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE courses (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, status VARCHAR(20)
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36) NOT NULL,
                    course_id VARCHAR(36) NOT NULL, semester_number INT NOT NULL,
                    is_mandatory BOOLEAN DEFAULT TRUE, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uq_curriculum_course (curriculum_version_id, course_id)
                )
                """);
        service = new AdminStructureImportService(jdbc);
    }

    @Test
    void importsTwoMajorsThatShareTheSameCode() {
        ImportSummary summary = service.importBatch(new ImportRequest(
                List.of(major("LLB", "Luật Kinh doanh"), major("LLB", "Luật Tài chính")),
                List.of(),
                List.of()));

        assertEquals(2, summary.majorsCreated());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM majors", Integer.class));
    }

    @Test
    void skipsAMajorThatAlreadyExistsWithTheSameCodeAndName() {
        service.importBatch(new ImportRequest(
                List.of(major("LLB", "Luật Kinh doanh")), List.of(), List.of()));

        ImportSummary second = service.importBatch(new ImportRequest(
                List.of(major("LLB", "Luật Kinh doanh"), major("LLB", "Luật Tài chính")),
                List.of(),
                List.of()));

        assertEquals(1, second.majorsCreated());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM majors", Integer.class));
    }

    @Test
    void linksCoursesToACohortAndCountsOnlyNewLinks() {
        jdbc.update("INSERT INTO courses (id, code, status) VALUES ('course-1', 'CSD201', 'ACTIVE')");

        service.importBatch(new ImportRequest(
                List.of(major("SE", "Software Engineering")),
                List.of(new CohortRow("SE", "Software Engineering", null, "K17",
                        null, null, "FA2022", "FA2026", true, "ACTIVE")),
                List.of()));

        ImportSummary second = service.importBatch(new ImportRequest(
                List.of(),
                List.of(),
                List.of(new CurriculumLinkRow("SE", "Software Engineering", "K17", "course-1", 3))));

        assertEquals(1, second.coursesLinked());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_courses", Integer.class));

        // A repeated link is not inserted twice and does not fail the request.
        ImportSummary third = service.importBatch(new ImportRequest(
                List.of(), List.of(),
                List.of(new CurriculumLinkRow("SE", "Software Engineering", "K17", "course-1", 3))));
        assertEquals(0, third.coursesLinked());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_courses", Integer.class));
    }

    @Test
    void createsTheSemesterThatACohortRefersTo() {
        ImportSummary summary = service.importBatch(new ImportRequest(
                List.of(major("SE", "Software Engineering")),
                List.of(new CohortRow("SE", "Software Engineering", null, "K17",
                        null, null, "FA2030", "FA2034", true, "ACTIVE")),
                List.of()));

        assertEquals(1, summary.cohortsCreated());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM semesters", Integer.class));
        assertTrue(jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_versions WHERE version = 'K17'", Integer.class) > 0);
    }

    private MajorRow major(String code, String name) {
        return new MajorRow(code, name, 140, new BigDecimal("4.0"), "ACTIVE");
    }
}
