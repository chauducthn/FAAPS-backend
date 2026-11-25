package vn.edu.fpt.faaps.infrastructure.config;

import java.util.Map;

final class LocalSeedCatalog {

    private static final String SYLLABUS_BASE = "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=";

    private final LocalSeedCatalogSql sql;
    private final LocalSeedCourseSql courseSql;

    LocalSeedCatalog(LocalSeedCatalogSql sql, LocalSeedCourseSql courseSql) {
        this.sql = sql;
        this.courseSql = courseSql;
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

        Map<String, String> courses = seedCoursesAndCurriculum(seK17Id, iaK17Id);
        seedAcademicRules(seK17Id);

        return new CatalogRefs(advisorRoleId, studentRoleId, adminUserId, seMajorId, iaMajorId,
                seK17Id, iaK17Id, fa2022Id, sp2025Id, su2025Id, fa2025Id, fa2026Id, sp2027Id, courses);
    }

    private Map<String, String> seedCoursesAndCurriculum(String seK17Id, String iaK17Id) {
        String coreCatId = courseSql.ensureCategory("CORE", "Core Courses",
                "Required foundational and professional core courses");
        String mathCatId = courseSql.ensureCategory("MATH", "Mathematics & Basic Sciences",
                "Foundational mathematics courses");
        String specCatId = courseSql.ensureCategory("SPEC", "Specialization & Capstone",
                "Major specialization and capstone courses");

        String prf192 = course("PRF192", "Programming Fundamentals", coreCatId,
                "Fundamental programming concepts and problem solving with C");
        String mae101 = course("MAE101", "Mathematics for Engineering", mathCatId,
                "Linear algebra, matrices, limits, derivatives, and integrals");
        String pro192 = course("PRO192", "Object-Oriented Programming", coreCatId,
                "Encapsulation, inheritance, polymorphism, and collections in Java");
        String mad101 = course("MAD101", "Discrete Mathematics", mathCatId,
                "Logic, sets, combinatorics, recursion, graphs, and trees");
        String csd201 = course("CSD201", "Data Structures and Algorithms", coreCatId,
                "Core data structures, algorithms, and complexity analysis");
        String dbi202 = course("DBI202", "Introduction to Databases", coreCatId,
                "Relational database design, SQL, normalization, and transactions");
        String prj301 = course("PRJ301", "Java Web Application Development", specCatId,
                "Servlets, JSP, MVC architecture, JDBC, and web development");
        String swe201c = course("SWE201c", "Introduction to Software Engineering", specCatId,
                "Software development life cycle, Agile/Scrum, requirements, and testing");
        String swr302 = course("SWR302", "Software Requirement", specCatId,
                "Requirement elicitation, analysis, specification (SRS), and validation");
        String swt301 = course("SWT301", "Software Testing", specCatId,
                "Unit testing, integration testing, and test automation");
        String swp391 = course("SWP391", "Application Development Project", specCatId,
                "Team-based software development project applying Agile/Scrum");
        String iai101 = course("IAI101", "Introduction to Information Assurance", coreCatId,
                "Security principles, threats, controls, and assurance practices");

        courseSql.ensurePrerequisite(pro192, prf192);
        courseSql.ensurePrerequisite(csd201, pro192);
        courseSql.ensurePrerequisite(prj301, pro192);
        courseSql.ensurePrerequisite(prj301, dbi202);
        courseSql.ensurePrerequisite(swe201c, pro192);
        courseSql.ensurePrerequisite(swr302, swe201c);
        courseSql.ensurePrerequisite(swt301, swe201c);
        courseSql.ensurePrerequisite(swp391, prj301);

        courseSql.ensureCurriculumCourse(seK17Id, prf192, 1);
        courseSql.ensureCurriculumCourse(seK17Id, mae101, 1);
        courseSql.ensureCurriculumCourse(seK17Id, pro192, 2);
        courseSql.ensureCurriculumCourse(seK17Id, mad101, 2);
        courseSql.ensureCurriculumCourse(seK17Id, csd201, 3);
        courseSql.ensureCurriculumCourse(seK17Id, dbi202, 3);
        courseSql.ensureCurriculumCourse(seK17Id, prj301, 4);
        courseSql.ensureCurriculumCourse(seK17Id, swe201c, 4);
        courseSql.ensureCurriculumCourse(seK17Id, swr302, 5);
        courseSql.ensureCurriculumCourse(seK17Id, swt301, 5);
        courseSql.ensureCurriculumCourse(seK17Id, swp391, 5);

        courseSql.ensureCurriculumCourse(iaK17Id, prf192, 1);
        courseSql.ensureCurriculumCourse(iaK17Id, pro192, 2);
        courseSql.ensureCurriculumCourse(iaK17Id, dbi202, 3);
        courseSql.ensureCurriculumCourse(iaK17Id, iai101, 3);

        return Map.ofEntries(
                Map.entry("PRF192", prf192), Map.entry("MAE101", mae101), Map.entry("PRO192", pro192),
                Map.entry("CSD201", csd201), Map.entry("DBI202", dbi202), Map.entry("PRJ301", prj301),
                Map.entry("SWR302", swr302), Map.entry("SWT301", swt301), Map.entry("SWP391", swp391));
    }

    private String course(String code, String name, String categoryId, String description) {
        return courseSql.ensureCourse(code, name, 3, categoryId, description, SYLLABUS_BASE + code);
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
