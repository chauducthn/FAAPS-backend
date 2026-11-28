package vn.edu.fpt.faaps.infrastructure.config;

import java.util.Map;

/**
 * Seeds the structural catalogue of a local install: roles, the Admin account,
 * majors, semesters, curriculum versions, course categories and the default
 * academic rules.
 *
 * <p>It deliberately creates NO sample courses, NO demo advisors and NO demo
 * students. The Admin imports the real course catalogue from FLM through
 * Admin &gt; Courses &gt; Import, and accounts are created through the app.
 */
final class LocalSeedCatalog {

    private final LocalSeedCatalogSql sql;
    private final LocalSeedCourseSql courseSql;

    LocalSeedCatalog(LocalSeedCatalogSql sql, LocalSeedCourseSql courseSql) {
        this.sql = sql;
        this.courseSql = courseSql;
    }

    /**
     * Creates the default FLM course categories if they are missing. This runs
     * for every local database, not only empty ones, because the browser import
     * check rejects any course whose category is unknown and the older local
     * seeds only created CORE, MATH and SPEC.
     */
    static void seedDefaultCourseCategories(LocalSeedCourseSql courseSql) {
        courseSql.ensureCategory("PREP", "Preparation Courses",
                "Pre-training preparation courses such as physical education, music, arts and life skills");
        courseSql.ensureCategory("GEN", "General Education & Soft Skills",
                "Foreign languages, humanities, general education and soft skills requirements");
        courseSql.ensureCategory("FOUND", "Foundation & Basic Sciences",
                "Foundational mathematics, computing and engineering science courses");
        courseSql.ensureCategory("OJT", "On-the-Job Training",
                "On-the-Job Training internship terms completed at a partner company");
        courseSql.ensureCategory("CAP", "Capstone Project",
                "Graduation capstone projects and thesis courses");
    }

    CatalogRefs seed() {
        String adminRoleId = sql.ensureRole("ADMIN", "Admin");
        String advisorRoleId = sql.ensureRole("ADVISOR", "Advisor");
        String studentRoleId = sql.ensureRole("STUDENT", "Student");

        String adminUserId = sql.ensureUser("admin@fpt.edu.vn", adminRoleId, "FAAPS System Administrator", "0901000001");
        sql.ensureAdminAccount(adminUserId);

        String seMajorId = sql.ensureMajor("SE", "Software Engineering",
                "Software engineering program focused on software design, development, testing, and delivery", 140, 4.0);
        String iaMajorId = sql.ensureMajor("IA", "Information Assurance",
                "Information assurance program focused on cybersecurity, risk management, and secure systems", 140, 4.0);
        String aiMajorId = sql.ensureMajor("AI", "Artificial Intelligence",
                "Artificial intelligence program focused on machine learning, deep learning, and NLP", 140, 4.0);

        String fa2022Id = sql.ensureSemester("FA2022", "Fall 2022", "2022-09-05", "2022-12-23", false);
        String sp2025Id = sql.ensureSemester("SP2025", "Spring 2025", "2025-01-06", "2025-04-26", false);
        String su2025Id = sql.ensureSemester("SU2025", "Summer 2025", "2025-05-05", "2025-08-23", false);
        String fa2025Id = sql.ensureSemester("FA2025", "Fall 2025", "2025-09-02", "2025-12-20", false);
        String fa2026Id = sql.ensureSemester("FA2026", "Fall 2026", "2026-09-07", "2026-12-26",
                !sql.hasCurrentSemester());
        String sp2027Id = sql.ensureSemester("SP2027", "Spring 2027", "2027-01-04", "2027-04-24", false);

        String seK17Id = sql.ensureCurriculumVersion(seMajorId, "K17", "Software Engineering K17", fa2022Id, true);
        String iaK17Id = sql.ensureCurriculumVersion(iaMajorId, "K17", "Information Assurance K17", fa2022Id, true);
        sql.ensureCurriculumVersion(aiMajorId, "K17", "Artificial Intelligence K17", fa2022Id, true);

        seedDefaultCategories();
        seedAcademicRules(seK17Id);

        return new CatalogRefs(advisorRoleId, studentRoleId, adminUserId, seMajorId, iaMajorId,
                seK17Id, iaK17Id, fa2022Id, sp2025Id, su2025Id, fa2025Id, fa2026Id, sp2027Id, Map.of());
    }

    /**
     * Seeds the seven FLM knowledge blocks. The browser import check rejects any
     * course whose category is unknown, so a FLM export cannot be imported
     * unless every block already exists. MATH is kept because it holds the
     * foundational mathematics courses; FOUND is the FLM name for the same
     * kind of block.
     */
    private void seedDefaultCategories() {
        seedDefaultCourseCategories(courseSql);
        courseSql.ensureCategory("CORE", "Core Courses",
                "Required foundational and professional core courses");
        courseSql.ensureCategory("MATH", "Mathematics & Basic Sciences",
                "Foundational mathematics courses");
        courseSql.ensureCategory("SPEC", "Specialization & Capstone",
                "Major specialization and capstone courses");
    }

    private void seedAcademicRules(String seK17Id) {
        courseSql.ensureAcademicRule(seK17Id, "GPA_WARNING",
                "{\"rule_name\":\"Academic GPA Warning Threshold\",\"grade\":5.0,\"min_gpa\":5.0}",
                "{\"max_credits\":15,\"require_advisor_review\":true}",
                true, 10, "Trigger academic warning when semester or cumulative GPA falls below 5.0");
        courseSql.ensureAcademicRule(seK17Id, "ACADEMIC_PROBATION",
                "{\"rule_name\":\"Academic Probation GPA Rule\",\"grade\":4.0,\"min_gpa\":4.0}",
                "{\"max_credits\":12,\"mandatory_advising\":true}",
                true, 20, "Place student on academic probation when cumulative GPA drops below 4.0");
        courseSql.ensureAcademicRule(seK17Id, "MAX_CREDITS_PER_SEMESTER",
                "{\"rule_name\":\"Standard Semester Credit Cap\",\"grade\":21,\"max_credits\":21}",
                "{\"hard_cap\":21}",
                false, 5, "Maximum credits a student in good standing may register per semester (21 credits)");
    }

    record CatalogRefs(
            String advisorRoleId, String studentRoleId, String adminUserId,
            String seMajorId, String iaMajorId, String seK17Id, String iaK17Id,
            String fa2022Id, String sp2025Id, String su2025Id, String fa2025Id,
            String fa2026Id, String sp2027Id, Map<String, String> courses) {}
}
