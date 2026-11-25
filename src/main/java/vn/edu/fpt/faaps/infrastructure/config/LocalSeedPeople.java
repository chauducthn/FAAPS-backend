package vn.edu.fpt.faaps.infrastructure.config;

import java.math.BigDecimal;
import java.util.Map;
import vn.edu.fpt.faaps.infrastructure.config.LocalSeedCatalog.CatalogRefs;

final class LocalSeedPeople {

    private final LocalSeedCatalogSql catalogSql;
    private final LocalSeedPeopleSql sql;
    private final LocalSeedAdvisingSql advisingSql;

    LocalSeedPeople(LocalSeedCatalogSql catalogSql, LocalSeedPeopleSql sql, LocalSeedAdvisingSql advisingSql) {
        this.catalogSql = catalogSql;
        this.sql = sql;
        this.advisingSql = advisingSql;
    }

    void seed(CatalogRefs refs) {
        String advisorHaId = catalogSql.ensureUser(
                "hantt@fpt.edu.vn", refs.advisorRoleId(), "Nguyễn Thị Thu Hà", "0902000001");
        sql.ensureAdvisor(advisorHaId, "ADV001", "Software Engineering & Capstone Advising");

        String advisorBaoId = catalogSql.ensureUser(
                "baopq@fpt.edu.vn", refs.advisorRoleId(), "Phạm Quốc Bảo", "0902000002");
        sql.ensureAdvisor(advisorBaoId, "ADV002", "Information Assurance & At-Risk Student Support");

        String studentLuongId = catalogSql.ensureUser(
                "luongnvse173012@fpt.edu.vn", refs.studentRoleId(), "Nguyễn Văn Lương", "0903000012");
        sql.ensureStudent(studentLuongId, "SE173012", refs.seMajorId(), refs.seK17Id(), refs.fa2022Id(),
                refs.sp2027Id(), 5, new BigDecimal("7.85"), 36, "ACTIVE");

        String studentLamId = catalogSql.ensureUser(
                "lamvtse173026@fpt.edu.vn", refs.studentRoleId(), "Võ Thanh Lâm", "0903000026");
        sql.ensureStudent(studentLamId, "SE173026", refs.seMajorId(), refs.seK17Id(), refs.fa2022Id(),
                refs.sp2027Id(), 5, new BigDecimal("4.35"), 24, "PROBATION");

        String studentKhoiId = catalogSql.ensureUser(
                "khoitmia173041@fpt.edu.vn", refs.studentRoleId(), "Trần Minh Khôi", "0903000041");
        sql.ensureStudent(studentKhoiId, "IA173041", refs.iaMajorId(), refs.iaK17Id(), refs.fa2022Id(),
                refs.sp2027Id(), 5, new BigDecimal("7.20"), 30, "ACTIVE");

        String studentAnhId = catalogSql.ensureUser(
                "anhlhia173055@fpt.edu.vn", refs.studentRoleId(), "Lê Hoàng Anh", "0903000055");
        sql.ensureStudent(studentAnhId, "IA173055", refs.iaMajorId(), refs.iaK17Id(), refs.fa2022Id(),
                refs.sp2027Id(), 4, new BigDecimal("4.60"), 18, "PROBATION");

        sql.ensureAdvisorAssignment(studentLuongId, advisorHaId, "K17-SE");
        sql.ensureAdvisorAssignment(studentLamId, advisorBaoId, "K17-SE");
        sql.ensureAdvisorAssignment(studentKhoiId, advisorHaId, "K17-IA");
        sql.ensureAdvisorAssignment(studentAnhId, advisorBaoId, "K17-IA");

        seedAcademicRecords(refs, studentLuongId, studentLamId, studentAnhId);
        seedPlansAndAdvising(refs, studentLuongId, studentLamId, advisorHaId, advisorBaoId);
    }

    private void seedAcademicRecords(CatalogRefs refs, String studentLuongId,
            String studentLamId, String studentAnhId) {
        Map<String, String> c = refs.courses();
        sql.ensureCourseRecord(studentLuongId, c.get("PRF192"), refs.sp2025Id(), new BigDecimal("8.20"), "PASSED", 3);
        sql.ensureCourseRecord(studentLuongId, c.get("PRO192"), refs.su2025Id(), new BigDecimal("8.00"), "PASSED", 3);
        sql.ensureCourseRecord(studentLuongId, c.get("CSD201"), refs.fa2025Id(), new BigDecimal("7.60"), "PASSED", 3);
        sql.ensureCourseRecord(studentLuongId, c.get("DBI202"), refs.fa2025Id(), new BigDecimal("8.10"), "PASSED", 3);

        sql.ensureCourseRecord(studentLamId, c.get("PRF192"), refs.sp2025Id(), new BigDecimal("5.50"), "PASSED", 3);
        sql.ensureCourseRecord(studentLamId, c.get("MAE101"), refs.sp2025Id(), new BigDecimal("4.20"), "FAILED", 0);
        sql.ensureCourseRecord(studentLamId, c.get("CSD201"), refs.fa2025Id(), new BigDecimal("3.80"), "FAILED", 0);
        sql.ensureCourseRecord(studentLamId, c.get("DBI202"), refs.fa2025Id(), new BigDecimal("4.50"), "FAILED", 0);

        sql.ensureSemesterSummary(studentLuongId, refs.fa2025Id(), 15, 15,
                new BigDecimal("7.85"), new BigDecimal("7.85"), "GOOD");
        sql.ensureSemesterSummary(studentLamId, refs.fa2025Id(), 15, 6,
                new BigDecimal("4.15"), new BigDecimal("4.35"), "PROBATION");

        advisingSql.ensureAttendanceConfig();
        advisingSql.ensureStudentAttendance(studentLuongId, c.get("PRJ301"), refs.fa2026Id(),
                20, 1, new BigDecimal("5.00"), "OK");
        advisingSql.ensureStudentAttendance(studentLamId, c.get("PRJ301"), refs.fa2026Id(),
                20, 5, new BigDecimal("25.00"), "FAIL");

        advisingSql.ensureAcademicWarning(studentLamId, refs.fa2026Id(), "LOW_GPA", "PROBATION",
                "Cumulative GPA (4.35) is below 5.00 and 3 courses were failed.",
                "Schedule an advising appointment to plan course retakes.", refs.adminUserId());
        advisingSql.ensureAcademicWarning(studentAnhId, refs.fa2026Id(), "LOW_GPA", "WARNING",
                "Cumulative GPA (4.60) is below the 5.00 academic standing threshold.",
                "Meet with your advisor before next semester registration.", refs.adminUserId());
    }

    private void seedPlansAndAdvising(CatalogRefs refs, String studentLuongId, String studentLamId,
            String advisorHaId, String advisorBaoId) {
        Map<String, String> c = refs.courses();
        String planId = advisingSql.ensureStudyPlan(studentLuongId, refs.sp2027Id(), "REGULAR", 9, "SUBMITTED");
        if (planId != null) {
            advisingSql.ensureStudyPlanCourse(planId, c.get("SWR302"), 3);
            advisingSql.ensureStudyPlanCourse(planId, c.get("SWT301"), 3);
            advisingSql.ensureStudyPlanCourse(planId, c.get("SWP391"), 3);
        }
        advisingSql.ensureAdvisingRequest(studentLuongId, advisorHaId, "PLAN_REVIEW",
                "Review Spring 2027 Capstone & Specialization Plan",
                "Em muốn nhờ cô xem giúp lộ trình đăng ký 3 môn SWR302, SWT301, SWP391.", "PENDING");
        advisingSql.ensureAdvisingSession(studentLamId, advisorBaoId, "SCHEDULED",
                "Room 204 - Alpha Building", "Academic probation recovery plan and course retake strategy",
                "Advised student to retake CSD201 and DBI202 next semester.", "COMPLETED");
    }
}
