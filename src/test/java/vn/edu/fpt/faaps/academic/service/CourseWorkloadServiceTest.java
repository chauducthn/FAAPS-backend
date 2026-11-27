package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import vn.edu.fpt.faaps.academic.controller.CourseWorkloadController;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;

class CourseWorkloadServiceTest {
    private JdbcTemplate jdbc;
    private CourseWorkloadService workloadService;
    private AdminCourseService adminCourses;
    private UUID catCore;
    private UUID catSoft;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:workload_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE course_categories (
                    id CHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(100) NOT NULL,
                    description TEXT
                )""");
        jdbc.execute("""
                CREATE TABLE courses (
                    id CHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL, name VARCHAR(255) NOT NULL,
                    credits INT NOT NULL, category_id CHAR(36) NOT NULL, description TEXT, objectives TEXT,
                    learning_outcomes TEXT, material_link VARCHAR(500), knowledge_block VARCHAR(100),
                    grading_structure TEXT, workload_level VARCHAR(20), status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )""");
        jdbc.execute("""
                CREATE TABLE course_prerequisites (
                    id CHAR(36) PRIMARY KEY, course_id CHAR(36) NOT NULL,
                    prerequisite_course_id CHAR(36) NOT NULL, group_no INT NOT NULL DEFAULT 1,
                    type VARCHAR(20) NOT NULL DEFAULT 'PREREQUISITE', minimum_score DECIMAL(4,2)
                )""");
        jdbc.execute("""
                CREATE TABLE curriculum_courses (
                    id CHAR(36) PRIMARY KEY, curriculum_version_id CHAR(36) NOT NULL, course_id CHAR(36) NOT NULL
                )""");
        jdbc.execute("""
                CREATE TABLE student_course_records (
                    id CHAR(36) PRIMARY KEY, student_id CHAR(36) NOT NULL, course_id CHAR(36) NOT NULL,
                    semester_id CHAR(36) NOT NULL, final_score DECIMAL(4,2), status VARCHAR(20)
                )""");

        catCore = UUID.randomUUID();
        catSoft = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'CORE', 'Core Major')", catCore.toString());
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'SOFT', 'Soft Skills')", catSoft.toString());
        workloadService = new CourseWorkloadService(jdbc);
        adminCourses = new AdminCourseService(jdbc);
    }

    @Test
    void layer2ManualOverrideTakesPrecedenceAndCanResetToAuto() {
        UUID courseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO courses (id, code, name, credits, category_id, grading_structure, status)
                VALUES (?, 'SSL101c', 'Academic Skills', 3, ?, 'Coursera completion', 'ACTIVE')
                """, courseId.toString(), catSoft.toString());

        CourseWorkload auto = workloadService.evaluateOne(courseId.toString());
        assertEquals(CourseWorkload.LEVEL_LIGHT, auto.level());
        assertEquals(CourseWorkload.SOURCE_RULE_BASED, auto.source());
        assertNull(auto.manualLevel());

        CourseWorkloadController controller = new CourseWorkloadController(workloadService);
        CourseWorkload overridden = controller.updateWorkload(
                courseId, new CourseWorkloadController.WorkloadOverrideRequest("heavy"));
        assertEquals(CourseWorkload.LEVEL_HEAVY, overridden.level());
        assertEquals(CourseWorkload.SOURCE_MANUAL, overridden.source());
        assertEquals(CourseWorkload.LEVEL_HEAVY, overridden.manualLevel());
        assertEquals(CourseWorkload.LEVEL_LIGHT, overridden.ruleLevel());

        CourseWorkload reset = controller.updateWorkload(
                courseId, new CourseWorkloadController.WorkloadOverrideRequest("AUTO"));
        assertEquals(CourseWorkload.LEVEL_LIGHT, reset.level());
        assertEquals(CourseWorkload.SOURCE_RULE_BASED, reset.source());
        assertNull(reset.manualLevel());

        assertThrows(IllegalArgumentException.class, () -> controller.updateWorkload(
                courseId, new CourseWorkloadController.WorkloadOverrideRequest("IMPOSSIBLE")));
        assertThrows(AdminCourseService.NotFound.class, () -> controller.updateWorkload(
                UUID.randomUUID(), new CourseWorkloadController.WorkloadOverrideRequest("HEAVY")));
    }

    @Test
    void layer3HistoricalStatsAdjustOnlyWhenSampleThresholdReached() {
        UUID courseId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO courses (id, code, name, credits, category_id, status)
                VALUES (?, 'PRJ301', 'Java Web Application', 3, ?, 'ACTIVE')
                """, courseId.toString(), catCore.toString());

        // Below threshold: 15 records (10 failed, 5 passed) + 20 IN_PROGRESS (excluded)
        insertRecords(courseId, 10, "FAILED", new BigDecimal("3.50"));
        insertRecords(courseId, 5, "PASSED", new BigDecimal("6.50"));
        insertRecords(courseId, 20, "IN_PROGRESS", null);

        CourseWorkload below = workloadService.evaluateOne(courseId.toString());
        assertEquals(CourseWorkload.SOURCE_RULE_BASED, below.source());
        assertEquals(15, below.stats().sampleSize());
        assertFalse(below.stats().sufficientSample());
        assertEquals(0, below.stats().dataAdjustment());
        assertEquals(new BigDecimal("66.7"), below.stats().failRate());

        // Push sample size to 35 (high fail rate + low avg score -> bumps MEDIUM to HEAVY)
        insertRecords(courseId, 10, "FAILED", new BigDecimal("4.00"));
        insertRecords(courseId, 10, "PASSED", new BigDecimal("6.00"));

        CourseWorkload adjusted = workloadService.evaluateOne(courseId.toString());
        assertEquals(CourseWorkload.SOURCE_DATA_ADJUSTED, adjusted.source());
        assertEquals(35, adjusted.stats().sampleSize());
        assertTrue(adjusted.stats().sufficientSample());
        assertTrue(adjusted.stats().dataAdjustment() > 0);
        assertEquals(CourseWorkload.LEVEL_HEAVY, adjusted.level());

        // Manual override still supersedes Layer 3
        CourseWorkload manual = workloadService.updateManualLevel(courseId, "LIGHT");
        assertEquals(CourseWorkload.LEVEL_LIGHT, manual.level());
        assertEquals(CourseWorkload.SOURCE_MANUAL, manual.source());
        assertTrue(manual.stats().sufficientSample());
    }

    @Test
    void adminListFiltersByEffectiveWorkloadAndHandlesCyclicPrereqsSafely() {
        var light = adminCourses.create("VOV124", "Vovinam 2", 2, catSoft, null, null, null, null, null, "ACTIVE", List.of());
        var heavy = adminCourses.create("SWP391", "Software Development Project", 3, catCore,
                null, null, null, null, "HEAVY", "ACTIVE", List.of());

        // Insert a cycle in course_prerequisites to verify cycle-safe depth calculation
        jdbc.update("INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, type) VALUES (?, ?, ?, 'PREREQUISITE')",
                UUID.randomUUID().toString(), light.id().toString(), heavy.id().toString());
        jdbc.update("INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, type) VALUES (?, ?, ?, 'PREREQUISITE')",
                UUID.randomUUID().toString(), heavy.id().toString(), light.id().toString());

        AdminCourseService.Page heavyPage = adminCourses.list(null, null, null, null, "HEAVY", 0, 20, "code,asc");
        assertEquals(1, heavyPage.totalElements());
        assertEquals("SWP391", heavyPage.items().get(0).code());
        assertEquals(CourseWorkload.LEVEL_HEAVY, heavyPage.items().get(0).workload().level());

        assertThrows(IllegalArgumentException.class,
                () -> adminCourses.list(null, null, null, null, "INVALID", 0, 20, "code,asc"));
    }

    @Test
    void preMigrationSchemaWithoutWorkloadColumnStillPreservesGradingStructure() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:workload_pre_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate preJdbc = new JdbcTemplate(ds);
        preJdbc.execute("CREATE TABLE course_categories (id CHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(100))");
        preJdbc.execute("""
                CREATE TABLE courses (
                    id CHAR(36) PRIMARY KEY, code VARCHAR(50), name VARCHAR(255), credits INT,
                    category_id CHAR(36), knowledge_block VARCHAR(100), grading_structure TEXT
                )""");
        String catId = UUID.randomUUID().toString();
        String cid = UUID.randomUUID().toString();
        preJdbc.update("INSERT INTO course_categories VALUES (?, 'CORE', 'Core Major')", catId);
        preJdbc.update("""
                INSERT INTO courses VALUES (?, 'CSD201', 'DSA', 3, ?, 'Core',
                'Lab 20% + Practical Exam (PE) 40% + Final Exam (FE) 40%')
                """, cid, catId);

        CourseWorkload w = new CourseWorkloadService(preJdbc).evaluateOne(cid);
        assertEquals(CourseWorkload.LEVEL_HEAVY, w.level());
        assertTrue(w.ruleReasons().stream().anyMatch(r -> r.contains("Practical Exam (PE)")));
    }

    private void insertRecords(UUID courseId, int count, String status, BigDecimal score) {
        String semId = UUID.randomUUID().toString();
        for (int i = 0; i < count; i++) {
            jdbc.update("""
                    INSERT INTO student_course_records (id, student_id, course_id, semester_id, final_score, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                    courseId.toString(), semId, score, status);
        }
    }
}
