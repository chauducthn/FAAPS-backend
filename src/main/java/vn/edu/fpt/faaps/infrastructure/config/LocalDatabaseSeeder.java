package vn.edu.fpt.faaps.infrastructure.config;

import java.math.BigDecimal;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Tracks the current existing database tables on startup (or when invoked explicitly)
 * and seeds baseline sample data if the core tables are empty.
 */
@Component
public class LocalDatabaseSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LocalDatabaseSeeder.class);

    private static final String DEFAULT_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private static final List<String> TRACKED_TABLES = List.of(
            "roles",
            "users",
            "majors",
            "semesters",
            "curriculum_versions",
            "course_categories",
            "courses",
            "course_prerequisites",
            "curriculum_courses",
            "academic_policies",
            "advisors",
            "students",
            "student_advisor_assignments",
            "student_course_records",
            "semester_summaries",
            "academic_warnings",
            "study_plans",
            "advising_requests",
            "advising_sessions"
    );

    private static final List<String> CORE_REQUIRED_TABLES = List.of(
            "majors",
            "semesters",
            "curriculum_versions",
            "courses",
            "academic_policies",
            "advisors",
            "students"
    );

    private final JdbcTemplate jdbc;
    private final boolean autoEnable;

    public LocalDatabaseSeeder(
            JdbcTemplate jdbc,
            @Value("${faaps.seed.auto-enable:false}") boolean autoEnable) {
        this.jdbc = jdbc;
        this.autoEnable = autoEnable;
    }

    public record DatabaseTrackingReport(
            Map<String, Integer> beforeCounts,
            Map<String, Integer> afterCounts,
            boolean seeded
    ) {}

    @Override
    public void run(ApplicationArguments args) {
        if (!autoEnable || !isLocalDatabaseConnection()) {
            return;
        }
        try {
            DatabaseTrackingReport report = trackAndSeedIfEmpty(false);
            if (report.seeded()) {
                log.info("FAAPS LocalDatabaseSeeder: Seeded empty local database tables. Before={}, After={}",
                        report.beforeCounts(), report.afterCounts());
            } else {
                log.info("FAAPS LocalDatabaseSeeder: Existing database data detected. Tracking snapshot={}",
                        report.beforeCounts());
            }
        } catch (Exception ex) {
            log.warn("FAAPS LocalDatabaseSeeder skipped automatic startup check: {}", ex.getMessage());
        }
    }

    private boolean isLocalDatabaseConnection() {
        DataSource ds = jdbc.getDataSource();
        if (ds == null) {
            return false;
        }
        try (Connection conn = ds.getConnection()) {
            String url = conn.getMetaData().getURL();
            if (url == null) {
                return false;
            }
            String lower = url.toLowerCase(Locale.ROOT);
            if (lower.startsWith("jdbc:h2:")) {
                return false;
            }
            return lower.contains("//localhost") || lower.contains("//127.0.0.1");
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Inspects current row counts across tracked tables.
     * Missing tables are recorded with count = -1.
     */
    public Map<String, Integer> inspectTableCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : TRACKED_TABLES) {
            counts.put(table, safeCount(table));
        }
        return counts;
    }

    private int safeCount(String table) {
        try {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return count == null ? 0 : count;
        } catch (Exception ex) {
            return -1;
        }
    }

    /**
     * Tracks the current existing DB and seeds sample data if any core required table is empty
     * (or if force is true).
     */
    public DatabaseTrackingReport trackAndSeedIfEmpty(boolean force) {
        Map<String, Integer> before = inspectTableCounts();

        // If core schema tables don't exist at all, do not fail startup
        for (String core : CORE_REQUIRED_TABLES) {
            if (before.getOrDefault(core, -1) < 0) {
                return new DatabaseTrackingReport(before, before, false);
            }
        }

        boolean needsSeed = force || CORE_REQUIRED_TABLES.stream()
                .anyMatch(table -> before.getOrDefault(table, 0) == 0);

        if (!needsSeed) {
            return new DatabaseTrackingReport(before, before, false);
        }

        seedBaselineData();
        Map<String, Integer> after = inspectTableCounts();
        return new DatabaseTrackingReport(before, after, true);
    }

    private void seedBaselineData() {
        // 1. Roles
        String adminRoleId = ensureRole("ADMIN", "Admin");
        String advisorRoleId = ensureRole("ADVISOR", "Advisor");
        String studentRoleId = ensureRole("STUDENT", "Student");

        // 2. Admin user
        String adminUserId = ensureUser("admin@fpt.edu.vn", adminRoleId, "FAAPS System Administrator", "0901000001");
        ensureAdminAccount(adminUserId);

        // 3. Majors
        String seMajorId = ensureMajor("SE", "Software Engineering",
                "Software engineering program focused on software design, development, testing, and delivery", 140, 4.0);
        String iaMajorId = ensureMajor("IA", "Information Assurance",
                "Information assurance program focused on cybersecurity, risk management, and secure systems", 140, 4.0);
        String aiMajorId = ensureMajor("AI", "Artificial Intelligence",
                "Artificial intelligence program focused on machine learning, deep learning, and NLP", 140, 4.0);

        // 4. Semesters
        String fa2022Id = ensureSemester("FA2022", "Fall 2022", "2022-09-05", "2022-12-23", false);
        String sp2025Id = ensureSemester("SP2025", "Spring 2025", "2025-01-06", "2025-04-26", false);
        String su2025Id = ensureSemester("SU2025", "Summer 2025", "2025-05-05", "2025-08-23", false);
        String fa2025Id = ensureSemester("FA2025", "Fall 2025", "2025-09-02", "2025-12-20", false);
        String fa2026Id = ensureSemester("FA2026", "Fall 2026", "2026-09-07", "2026-12-26", !hasCurrentSemester());
        String sp2027Id = ensureSemester("SP2027", "Spring 2027", "2027-01-04", "2027-04-24", false);

        // 5. Curriculum Versions
        String seK17Id = ensureCurriculumVersion(seMajorId, "K17", "Software Engineering K17", fa2022Id, true);
        String iaK17Id = ensureCurriculumVersion(iaMajorId, "K17", "Information Assurance K17", fa2022Id, true);
        ensureCurriculumVersion(aiMajorId, "K17", "Artificial Intelligence K17", fa2022Id, true);

        // 6. Course Categories
        String coreCatId = ensureCategory("CORE", "Core Courses", "Required foundational and professional core courses");
        String mathCatId = ensureCategory("MATH", "Mathematics & Basic Sciences", "Foundational mathematics courses");
        String specCatId = ensureCategory("SPEC", "Specialization & Capstone", "Major specialization and capstone courses");

        // 7. Courses (with materialLink in learning_outcomes)
        String prf192Id = ensureCourse("PRF192", "Programming Fundamentals", 3, coreCatId,
                "Fundamental programming concepts and problem solving with C",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRF192");
        String mae101Id = ensureCourse("MAE101", "Mathematics for Engineering", 3, mathCatId,
                "Linear algebra, matrices, limits, derivatives, and integrals",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=MAE101");
        String pro192Id = ensureCourse("PRO192", "Object-Oriented Programming", 3, coreCatId,
                "Encapsulation, inheritance, polymorphism, and collections in Java",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRO192");
        String mad101Id = ensureCourse("MAD101", "Discrete Mathematics", 3, mathCatId,
                "Logic, sets, combinatorics, recursion, graphs, and trees",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=MAD101");
        String csd201Id = ensureCourse("CSD201", "Data Structures and Algorithms", 3, coreCatId,
                "Core data structures, algorithms, and complexity analysis",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=CSD201");
        String dbi202Id = ensureCourse("DBI202", "Introduction to Databases", 3, coreCatId,
                "Relational database design, SQL, normalization, and transactions",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=DBI202");
        String prj301Id = ensureCourse("PRJ301", "Java Web Application Development", 3, specCatId,
                "Servlets, JSP, MVC architecture, JDBC, and web development",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRJ301");
        String swe201cId = ensureCourse("SWE201c", "Introduction to Software Engineering", 3, specCatId,
                "Software development life cycle, Agile/Scrum, requirements, and testing",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWE201c");
        String swr302Id = ensureCourse("SWR302", "Software Requirement", 3, specCatId,
                "Requirement elicitation, analysis, specification (SRS), and validation",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWR302");
        String swt301Id = ensureCourse("SWT301", "Software Testing", 3, specCatId,
                "Unit testing, integration testing, and test automation",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWT301");
        String swp391Id = ensureCourse("SWP391", "Application Development Project", 3, specCatId,
                "Team-based software development project applying Agile/Scrum",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=SWP391");
        String iai101Id = ensureCourse("IAI101", "Introduction to Information Assurance", 3, coreCatId,
                "Security principles, threats, controls, and assurance practices",
                "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=IAI101");

        // 8. Course Prerequisites
        ensurePrerequisite(pro192Id, prf192Id);
        ensurePrerequisite(csd201Id, pro192Id);
        ensurePrerequisite(prj301Id, pro192Id);
        ensurePrerequisite(prj301Id, dbi202Id);
        ensurePrerequisite(swe201cId, pro192Id);
        ensurePrerequisite(swr302Id, swe201cId);
        ensurePrerequisite(swt301Id, swe201cId);
        ensurePrerequisite(swp391Id, prj301Id);

        // 9. Curriculum Courses
        ensureCurriculumCourse(seK17Id, prf192Id, 1);
        ensureCurriculumCourse(seK17Id, mae101Id, 1);
        ensureCurriculumCourse(seK17Id, pro192Id, 2);
        ensureCurriculumCourse(seK17Id, mad101Id, 2);
        ensureCurriculumCourse(seK17Id, csd201Id, 3);
        ensureCurriculumCourse(seK17Id, dbi202Id, 3);
        ensureCurriculumCourse(seK17Id, prj301Id, 4);
        ensureCurriculumCourse(seK17Id, swe201cId, 4);
        ensureCurriculumCourse(seK17Id, swr302Id, 5);
        ensureCurriculumCourse(seK17Id, swt301Id, 5);
        ensureCurriculumCourse(seK17Id, swp391Id, 5);

        ensureCurriculumCourse(iaK17Id, prf192Id, 1);
        ensureCurriculumCourse(iaK17Id, pro192Id, 2);
        ensureCurriculumCourse(iaK17Id, dbi202Id, 3);
        ensureCurriculumCourse(iaK17Id, iai101Id, 3);

        // 10. Academic Rules (rule_name & grade JSON for updated Admin Academic Rules UI)
        ensureAcademicRule(seK17Id, "GPA_WARNING",
                "{\"rule_name\":\"Academic GPA Warning Threshold\",\"grade\":5.0,\"min_gpa\":5.0}",
                "{\"max_credits\":15,\"require_advisor_review\":true}",
                true, 10, "Trigger academic warning when semester or cumulative GPA falls below 5.0");
        ensureAcademicRule(seK17Id, "ACADEMIC_PROBATION",
                "{\"rule_name\":\"Academic Probation GPA Rule\",\"grade\":4.0,\"min_gpa\":4.0}",
                "{\"max_credits\":12,\"mandatory_advising\":true}",
                true, 20, "Place student on academic probation when cumulative GPA drops below 4.0");
        ensureAcademicRule(seK17Id, "MAX_CREDITS_PER_SEMESTER",
                "{\"rule_name\":\"Standard Semester Credit Cap\",\"grade\":21,\"max_credits\":21}",
                "{\"hard_cap\":21}",
                false, 5, "Maximum credits a student in good standing may register per semester (21 credits)");

        // 11. Advisors
        String advisorHaId = ensureUser("hantt@fpt.edu.vn", advisorRoleId, "Nguyễn Thị Thu Hà", "0902000001");
        ensureAdvisor(advisorHaId, "ADV001", "Software Engineering & Capstone Advising");

        String advisorBaoId = ensureUser("baopq@fpt.edu.vn", advisorRoleId, "Phạm Quốc Bảo", "0902000002");
        ensureAdvisor(advisorBaoId, "ADV002", "Information Assurance & At-Risk Student Support");

        // 12. Students
        String studentLuongId = ensureUser("luongnvse173012@fpt.edu.vn", studentRoleId, "Nguyễn Văn Lương", "0903000012");
        ensureStudent(studentLuongId, "SE173012", seMajorId, seK17Id, fa2022Id, sp2027Id, 5,
                new BigDecimal("7.85"), 36, "ACTIVE");

        String studentLamId = ensureUser("lamvtse173026@fpt.edu.vn", studentRoleId, "Võ Thanh Lâm", "0903000026");
        ensureStudent(studentLamId, "SE173026", seMajorId, seK17Id, fa2022Id, sp2027Id, 5,
                new BigDecimal("4.35"), 24, "PROBATION");

        String studentKhoiId = ensureUser("khoitmia173041@fpt.edu.vn", studentRoleId, "Trần Minh Khôi", "0903000041");
        ensureStudent(studentKhoiId, "IA173041", iaMajorId, iaK17Id, fa2022Id, sp2027Id, 5,
                new BigDecimal("7.20"), 30, "ACTIVE");

        String studentAnhId = ensureUser("anhlhia173055@fpt.edu.vn", studentRoleId, "Lê Hoàng Anh", "0903000055");
        ensureStudent(studentAnhId, "IA173055", iaMajorId, iaK17Id, fa2022Id, sp2027Id, 4,
                new BigDecimal("4.60"), 18, "PROBATION");

        // 13. Student-Advisor Assignments
        ensureAdvisorAssignment(studentLuongId, advisorHaId, "K17-SE");
        ensureAdvisorAssignment(studentLamId, advisorBaoId, "K17-SE");
        ensureAdvisorAssignment(studentKhoiId, advisorHaId, "K17-IA");
        ensureAdvisorAssignment(studentAnhId, advisorBaoId, "K17-IA");

        // 14. Student Course Records & Semester Summaries
        ensureCourseRecord(studentLuongId, prf192Id, sp2025Id, new BigDecimal("8.20"), "PASSED", 3);
        ensureCourseRecord(studentLuongId, pro192Id, su2025Id, new BigDecimal("8.00"), "PASSED", 3);
        ensureCourseRecord(studentLuongId, csd201Id, fa2025Id, new BigDecimal("7.60"), "PASSED", 3);
        ensureCourseRecord(studentLuongId, dbi202Id, fa2025Id, new BigDecimal("8.10"), "PASSED", 3);

        ensureCourseRecord(studentLamId, prf192Id, sp2025Id, new BigDecimal("5.50"), "PASSED", 3);
        ensureCourseRecord(studentLamId, mae101Id, sp2025Id, new BigDecimal("4.20"), "FAILED", 0);
        ensureCourseRecord(studentLamId, csd201Id, fa2025Id, new BigDecimal("3.80"), "FAILED", 0);
        ensureCourseRecord(studentLamId, dbi202Id, fa2025Id, new BigDecimal("4.50"), "FAILED", 0);

        ensureSemesterSummary(studentLuongId, fa2025Id, 15, 15, new BigDecimal("7.85"), new BigDecimal("7.85"), "GOOD");
        ensureSemesterSummary(studentLamId, fa2025Id, 15, 6, new BigDecimal("4.15"), new BigDecimal("4.35"), "PROBATION");

        // 15. Academic Warnings & Attendance
        ensureAttendanceConfig();
        ensureStudentAttendance(studentLuongId, prj301Id, fa2026Id, 20, 1, new BigDecimal("5.00"), "OK");
        ensureStudentAttendance(studentLamId, prj301Id, fa2026Id, 20, 5, new BigDecimal("25.00"), "FAIL");

        ensureAcademicWarning(studentLamId, fa2026Id, "LOW_GPA", "PROBATION",
                "Cumulative GPA (4.35) is below 5.00 and 3 courses were failed.",
                "Schedule an advising appointment to plan course retakes.", adminUserId);
        ensureAcademicWarning(studentAnhId, fa2026Id, "LOW_GPA", "WARNING",
                "Cumulative GPA (4.60) is below the 5.00 academic standing threshold.",
                "Meet with your advisor before next semester registration.", adminUserId);

        // 16. Study Plans, Advising Requests & Sessions
        String planId = ensureStudyPlan(studentLuongId, sp2027Id, "REGULAR", 9, "SUBMITTED");
        if (planId != null) {
            ensureStudyPlanCourse(planId, swr302Id, 3);
            ensureStudyPlanCourse(planId, swt301Id, 3);
            ensureStudyPlanCourse(planId, swp391Id, 3);
        }
        ensureAdvisingRequest(studentLuongId, advisorHaId, "PLAN_REVIEW",
                "Review Spring 2027 Capstone & Specialization Plan",
                "Em muốn nhờ cô xem giúp lộ trình đăng ký 3 môn SWR302, SWT301, SWP391.", "PENDING");
        ensureAdvisingSession(studentLamId, advisorBaoId, "SCHEDULED",
                "Room 204 - Alpha Building", "Academic probation recovery plan and course retake strategy",
                "Advised student to retake CSD201 and DBI202 next semester.", "COMPLETED");
    }

    private String ensureRole(String code, String name) {
        List<String> existing = jdbc.query(
                "SELECT id FROM roles WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO roles (id, code, name) VALUES (?, ?, ?)", id, code, name);
        } catch (Exception ex) {
            jdbc.update("INSERT INTO roles (id, code) VALUES (?, ?)", id, code);
        }
        return id;
    }

    private String ensureUser(String email, String roleId, String fullName, String phone) {
        List<String> existing = jdbc.query(
                "SELECT id FROM users WHERE LOWER(email) = LOWER(?)",
                (rs, i) -> rs.getString("id"), email);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (id, role_id, email, password_hash, auth_provider, full_name, phone, status)
                VALUES (?, ?, ?, ?, 'LOCAL', ?, ?, 'ACTIVE')
                """, id, roleId, email, DEFAULT_BCRYPT_HASH, fullName, phone);
        return id;
    }

    private void ensureAdminAccount(String userId) {
        if (safeCount("admin_account") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_account WHERE singleton_id = 1", Integer.class);
        if (count != null && count == 0) {
            jdbc.update("INSERT INTO admin_account (singleton_id, user_id) VALUES (1, ?)", userId);
        }
    }

    private String ensureMajor(String code, String name, String description, int totalCredits, double durationYears) {
        List<String> existing = jdbc.query(
                "SELECT id FROM majors WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO majors (id, code, name, description, total_credits, duration_years, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, code, name, description, totalCredits, BigDecimal.valueOf(durationYears));
        return id;
    }

    private boolean hasCurrentSemester() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM semesters WHERE is_current = TRUE", Integer.class);
        return count != null && count > 0;
    }

    private String ensureSemester(String code, String name, String startDate, String endDate, boolean isCurrent) {
        List<String> existing = jdbc.query(
                "SELECT id FROM semesters WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO semesters (id, code, name, start_date, end_date, is_current)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, code, name, java.sql.Date.valueOf(startDate), java.sql.Date.valueOf(endDate), isCurrent);
        return id;
    }

    private String ensureCurriculumVersion(String majorId, String version, String name, String semId, boolean isCurrent) {
        List<String> existing = jdbc.query(
                "SELECT id FROM curriculum_versions WHERE major_id = ? AND version = ?",
                (rs, i) -> rs.getString("id"), majorId, version);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO curriculum_versions (id, major_id, version, name, effective_from_semester_id, is_current, status)
                VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')
                """, id, majorId, version, name, semId, isCurrent);
        return id;
    }

    private String ensureCategory(String code, String name, String description) {
        if (safeCount("course_categories") < 0) {
            return UUID.randomUUID().toString();
        }
        List<String> existing = jdbc.query(
                "SELECT id FROM course_categories WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("INSERT INTO course_categories (id, code, name, description) VALUES (?, ?, ?, ?)",
                    id, code, name, description);
        } catch (Exception ex) {
            jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, ?, ?)", id, code, name);
        }
        return id;
    }

    private String ensureCourse(String code, String name, int credits, String categoryId,
                                String description, String materialLink) {
        List<String> existing = jdbc.query(
                "SELECT id FROM courses WHERE code = ?",
                (rs, i) -> rs.getString("id"), code);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO courses (id, code, name, credits, category_id, description, material_link, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, code, name, credits, categoryId, description, materialLink);
        } catch (Exception ex) {
            jdbc.update("""
                    INSERT INTO courses (id, code, name, credits, category_id, status)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, code, name, credits, categoryId);
        }
        return id;
    }

    private void ensurePrerequisite(String courseId, String prereqCourseId) {
        if (safeCount("course_prerequisites") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM course_prerequisites WHERE course_id = ? AND prerequisite_course_id = ?",
                Integer.class, courseId, prereqCourseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO course_prerequisites (id, course_id, prerequisite_course_id, group_no, type)
                    VALUES (?, ?, ?, 1, 'PREREQUISITE')
                    """, UUID.randomUUID().toString(), courseId, prereqCourseId);
        }
    }

    private void ensureCurriculumCourse(String curriculumVersionId, String courseId, int semesterNumber) {
        if (safeCount("curriculum_courses") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id = ? AND course_id = ?",
                Integer.class, curriculumVersionId, courseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO curriculum_courses (id, curriculum_version_id, course_id, semester_number, is_mandatory)
                    VALUES (?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(), curriculumVersionId, courseId, semesterNumber);
        }
    }

    private void ensureAcademicRule(String curriculumVersionId, String policyType, String conditionExpr,
                                    String restrictionExpr, boolean approvalRequired, int priority, String description) {
        if (safeCount("academic_policies") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic_policies WHERE policy_type = ?",
                Integer.class, policyType);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO academic_policies (
                        id, curriculum_version_id, policy_type, condition_expression,
                        restriction_expression, approval_required, priority, description, is_active
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(), curriculumVersionId, policyType,
                    conditionExpr, restrictionExpr, approvalRequired, priority, description);
        }
    }

    private void ensureAdvisor(String userId, String advisorCode, String specialization) {
        if (safeCount("advisors") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM advisors WHERE user_id = ?", Integer.class, userId);
        if (count != null && count == 0) {
            try {
                jdbc.update("""
                        INSERT INTO advisors (user_id, advisor_code, specialization, max_advisees, status)
                        VALUES (?, ?, ?, 30, 'ACTIVE')
                        """, userId, advisorCode, specialization);
            } catch (Exception ex) {
                jdbc.update("INSERT INTO advisors (user_id, max_advisees, status) VALUES (?, 30, 'ACTIVE')", userId);
            }
        }
    }

    private void ensureStudent(String userId, String studentCode, String majorId, String curriculumVersionId,
                               String enrollmentSemId, String expectedGradSemId, int currentSem,
                               BigDecimal currentGpa, int totalCreditsEarned, String academicStatus) {
        if (safeCount("students") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM students WHERE user_id = ? OR student_code = ?",
                Integer.class, userId, studentCode);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO students (
                        user_id, student_code, major_id, curriculum_version_id,
                        enrollment_semester_id, expected_graduation_semester_id,
                        current_semester, current_gpa, total_credits_earned, academic_status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, userId, studentCode, majorId, curriculumVersionId,
                    enrollmentSemId, expectedGradSemId, currentSem, currentGpa, totalCreditsEarned, academicStatus);
        }
    }

    private void ensureAdvisorAssignment(String studentId, String advisorId, String groupName) {
        if (safeCount("student_advisor_assignments") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_advisor_assignments WHERE student_id = ? AND status = 'ACTIVE'",
                Integer.class, studentId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO student_advisor_assignments (
                        id, student_id, advisor_id, group_name, assigned_date, is_primary, status
                    ) VALUES (?, ?, ?, ?, CURRENT_DATE, TRUE, 'ACTIVE')
                    """, UUID.randomUUID().toString(), studentId, advisorId, groupName);
        }
    }

    private void ensureCourseRecord(String studentId, String courseId, String semesterId,
                                    BigDecimal finalScore, String status, int creditsEarned) {
        if (safeCount("student_course_records") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_course_records WHERE student_id = ? AND course_id = ?",
                Integer.class, studentId, courseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO student_course_records (
                        id, student_id, course_id, semester_id, final_score, status, attempt_number, credits_earned
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    """, UUID.randomUUID().toString(), studentId, courseId, semesterId, finalScore, status, creditsEarned);
        }
    }

    private void ensureSemesterSummary(String studentId, String semesterId, int enrolled, int earned,
                                       BigDecimal semGpa, BigDecimal cumGpa, String standing) {
        if (safeCount("semester_summaries") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM semester_summaries WHERE student_id = ? AND semester_id = ?",
                Integer.class, studentId, semesterId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO semester_summaries (
                        id, student_id, semester_id, total_credits_enrolled, total_credits_earned,
                        semester_gpa, cumulative_gpa, academic_standing
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, semesterId, enrolled, earned, semGpa, cumGpa, standing);
        }
    }

    private void ensureAttendanceConfig() {
        if (safeCount("attendance_default_config") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM attendance_default_config WHERE singleton_id = 1", Integer.class);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO attendance_default_config (singleton_id, total_sessions, min_attendance_percent, warn_absent_sessions)
                    VALUES (1, 20, 80, 3)
                    """);
        }
    }

    private void ensureStudentAttendance(String studentId, String courseId, String semesterId,
                                         int totalSessions, int absentSessions, BigDecimal absentRate, String result) {
        if (safeCount("student_course_attendance") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM student_course_attendance WHERE student_id = ? AND course_id = ? AND semester_id = ?",
                Integer.class, studentId, courseId, semesterId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO student_course_attendance (
                        id, student_id, course_id, semester_id, total_sessions, absent_sessions, absent_rate, result
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, courseId, semesterId,
                    totalSessions, absentSessions, absentRate, result);
        }
    }

    private void ensureAcademicWarning(String studentId, String semesterId, String warningType,
                                       String severity, String description, String actionRequired, String issuedByUserId) {
        if (safeCount("academic_warnings") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM academic_warnings WHERE student_id = ? AND semester_id = ? AND warning_type = ?",
                Integer.class, studentId, semesterId, warningType);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO academic_warnings (
                        id, student_id, semester_id, warning_type, severity, description, action_required, issued_by_user_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, semesterId, warningType,
                    severity, description, actionRequired, issuedByUserId);
        }
    }

    private String ensureStudyPlan(String studentId, String semesterId, String planType, int totalCredits, String status) {
        if (safeCount("study_plans") < 0) {
            return null;
        }
        List<String> existing = jdbc.query(
                "SELECT id FROM study_plans WHERE student_id = ? AND semester_id = ? AND version = 1",
                (rs, i) -> rs.getString("id"), studentId, semesterId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO study_plans (id, student_id, semester_id, version, is_current, plan_type, total_credits_planned, status)
                VALUES (?, ?, ?, 1, TRUE, ?, ?, ?)
                """, id, studentId, semesterId, planType, totalCredits, status);
        return id;
    }

    private void ensureStudyPlanCourse(String planId, String courseId, int plannedCredits) {
        if (safeCount("study_plan_courses") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM study_plan_courses WHERE study_plan_id = ? AND course_id = ?",
                Integer.class, planId, courseId);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO study_plan_courses (id, study_plan_id, course_id, planned_credits, priority)
                    VALUES (?, ?, ?, ?, 'HIGH')
                    """, UUID.randomUUID().toString(), planId, courseId, plannedCredits);
        }
    }

    private void ensureAdvisingRequest(String studentId, String advisorId, String requestType,
                                       String title, String description, String status) {
        if (safeCount("advising_requests") < 0) {
            return;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM advising_requests WHERE student_id = ? AND title = ?",
                Integer.class, studentId, title);
        if (count != null && count == 0) {
            jdbc.update("""
                    INSERT INTO advising_requests (id, student_id, advisor_id, request_type, title, description, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), studentId, advisorId, requestType, title, description, status);
        }
    }

    private void ensureAdvisingSession(String studentId, String advisorId, String sessionType,
                                       String location, String agenda, String summary, String status) {
        if (safeCount("advising_sessions") < 0) {
            return;
        }
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM advising_sessions WHERE student_id = ? AND agenda = ?",
                    Integer.class, studentId, agenda);
            if (count != null && count == 0) {
                jdbc.update("""
                        INSERT INTO advising_sessions (id, student_id, advisor_id, session_type, scheduled_at, location, agenda, summary, status)
                        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?)
                        """, UUID.randomUUID().toString(), studentId, advisorId, sessionType, location, agenda, summary, status);
            }
        } catch (Exception ex) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM advising_sessions WHERE student_id = ? AND advisor_id = ?",
                    Integer.class, studentId, advisorId);
            if (count != null && count == 0) {
                jdbc.update("""
                        INSERT INTO advising_sessions (id, student_id, advisor_id, scheduled_at, status)
                        VALUES (?, ?, ?, CURRENT_TIMESTAMP, ?)
                        """, UUID.randomUUID().toString(), studentId, advisorId, status);
            }
        }
    }
}
