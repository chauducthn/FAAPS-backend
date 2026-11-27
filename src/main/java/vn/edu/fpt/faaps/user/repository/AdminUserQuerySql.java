package vn.edu.fpt.faaps.user.repository;

final class AdminUserQuerySql {
    private AdminUserQuerySql() {}

    static String advisorCohortJoin(boolean hasCohortAdvisors) {
        if (!hasCohortAdvisors) return "";
        return " LEFT JOIN ("
                + " SELECT adv.advisor_id, MIN(cv2.id) cohort_id, MIN(cv2.major_id) major_id,"
                + " MIN(m2.name) major_name, MIN(cv2.name) curriculum_name,"
                + " GROUP_CONCAT(DISTINCT m2.code ORDER BY m2.code SEPARATOR ', ') major_code,"
                + " GROUP_CONCAT(DISTINCT cv2.version ORDER BY cv2.version SEPARATOR ', ') cohort"
                + " FROM ("
                + "   SELECT advisor_id, cohort_id FROM cohort_advisors WHERE is_active = TRUE"
                + "   UNION"
                + "   SELECT sa.advisor_id, st.curriculum_version_id cohort_id"
                + "   FROM student_advisor_assignments sa JOIN students st ON st.user_id = sa.student_id"
                + "   WHERE sa.status = 'ACTIVE' AND sa.end_date IS NULL AND st.curriculum_version_id IS NOT NULL"
                + " ) adv"
                + " JOIN curriculum_versions cv2 ON cv2.id = adv.cohort_id"
                + " JOIN majors m2 ON m2.id = cv2.major_id"
                + " GROUP BY adv.advisor_id"
                + ") ac ON ac.advisor_id = u.id";
    }

    static String resolveSortOrder(String sort, String majorExpr, String cohortExpr) {
        return switch (sort) {
            case "createdAt,asc" -> "u.created_at ASC, u.id ASC";
            case "createdAt,desc" -> "u.created_at DESC, u.id DESC";
            case "fullName,asc" -> "u.full_name ASC, u.id ASC";
            case "fullName,desc" -> "u.full_name DESC, u.id DESC";
            case "userId,asc" -> "COALESCE(s.student_code, u.email) ASC, u.id ASC";
            case "userId,desc" -> "COALESCE(s.student_code, u.email) DESC, u.id DESC";
            case "email,asc" -> "u.email ASC, u.id ASC";
            case "email,desc" -> "u.email DESC, u.id DESC";
            case "role,asc" -> "r.code ASC, u.full_name ASC, u.id ASC";
            case "role,desc" -> "r.code DESC, u.full_name ASC, u.id DESC";
            case "major,asc" -> "COALESCE(" + majorExpr + ", 'ZZZZ') ASC, u.full_name ASC, u.id ASC";
            case "major,desc" -> "COALESCE(" + majorExpr + ", '') DESC, u.full_name ASC, u.id DESC";
            case "cohort,asc" -> "COALESCE(" + cohortExpr + ", 'ZZZZ') ASC, u.full_name ASC, u.id ASC";
            case "cohort,desc" -> "COALESCE(" + cohortExpr + ", '') DESC, u.full_name ASC, u.id DESC";
            case "status,asc" -> "u.status ASC, u.full_name ASC, u.id ASC";
            case "status,desc" -> "u.status DESC, u.full_name ASC, u.id DESC";
            default -> throw new IllegalArgumentException("sort is invalid");
        };
    }

    static String resolveIdentifier(String role, String userCode, String email) {
        if ("ADVISOR".equals(role) && email != null) {
            int at = email.indexOf('@');
            if (at > 0) return email.substring(0, at);
        }
        return userCode;
    }
}
