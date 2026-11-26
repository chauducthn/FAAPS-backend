package vn.edu.fpt.faaps.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import vn.edu.fpt.faaps.common.validation.IdentityInput;
import vn.edu.fpt.faaps.common.validation.StudentCodeValidator;
import vn.edu.fpt.faaps.user.service.AdminUserImportLoader.CohortInfo;
import vn.edu.fpt.faaps.user.service.AdminUserImportLoader.LoadedRefs;

/** Atomic batch import of student and advisor accounts from Excel spreadsheets. */
@Service
public class AdminUserImportService {

    private static final int BATCH_SIZE = 500;
    private static final int DEFAULT_MAX_ADVISEES = 30;
    private static final String INSERT_USER_SQL = "INSERT INTO users (id, role_id, email, auth_provider, full_name, phone, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'GOOGLE', ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    private static final String INSERT_STUDENT_SQL = "INSERT INTO students (user_id, student_code, major_id, curriculum_version_id, "
            + "enrollment_semester_id, expected_graduation_semester_id, current_semester, current_gpa, "
            + "total_credits_earned, academic_status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
    private static final String INSERT_ADVISOR_SQL = "INSERT INTO advisors (user_id, advisor_code, max_advisees, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";

    private final JdbcTemplate jdbc;

    public AdminUserImportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserImportRow(String role, String email, String fullName, String phone, String studentCode,
            String majorCode, String cohort, String enrollmentSemester, Integer currentSemester, Integer maxAdvisees) {
    }

    public record ImportUsersRequest(List<UserImportRow> items) {
    }

    public record ImportUsersSummary(int created, int studentsCreated, int advisorsCreated, int skipped) {
    }

    @Transactional
    public ImportUsersSummary importBatch(ImportUsersRequest request) {
        List<UserImportRow> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No users provided for import.");
        }
        LoadedRefs refs = AdminUserImportLoader.preload(jdbc);
        List<Object[]> userRows = new ArrayList<>();
        List<Object[]> studentRows = new ArrayList<>();
        List<Object[]> advisorRows = new ArrayList<>();
        int skipped = 0;

        for (int i = 0; i < items.size(); i++) {
            int rowNum = i + 2;
            UserImportRow row = items.get(i);
            String role = upper(row.role());
            if (!"STUDENT".equals(role) && !"ADVISOR".equals(role)) {
                throw new IllegalArgumentException("Row " + rowNum + ": role must be STUDENT or ADVISOR.");
            }
            String roleId = refs.roleIds().get(role);
            if (roleId == null) {
                throw new IllegalArgumentException("Role " + role + " is not configured.");
            }
            String email = IdentityInput.email(row.email());
            String fullName = normalizeFullName(row.fullName(), rowNum);
            String phone = normalizePhone(row.phone(), rowNum);
            if (refs.emails().contains(email)) {
                skipped++;
                continue;
            }
            UUID userId = UUID.randomUUID();
            if ("STUDENT".equals(role)) {
                Object[] student = buildStudentRow(userId, row, rowNum, refs);
                if (student == null) {
                    skipped++;
                    continue;
                }
                studentRows.add(student);
            } else {
                advisorRows.add(buildAdvisorRow(userId, email, row.maxAdvisees(), rowNum));
            }
            refs.emails().add(email);
            userRows.add(new Object[] {userId.toString(), roleId, email, fullName, phone});
        }

        batchInsert(INSERT_USER_SQL, userRows);
        batchInsert(INSERT_STUDENT_SQL, studentRows);
        batchInsert(INSERT_ADVISOR_SQL, advisorRows);
        return new ImportUsersSummary(userRows.size(), studentRows.size(), advisorRows.size(), skipped);
    }

    private Object[] buildStudentRow(UUID userId, UserImportRow row, int rowNum, LoadedRefs refs) {
        String code = StudentCodeValidator.validateAndNormalize(row.studentCode());
        if (refs.studentCodes().contains(code)) {
            return null;
        }
        int currentSem = row.currentSemester() == null ? 1 : row.currentSemester();
        if (currentSem < 1) {
            throw new IllegalArgumentException("Row " + rowNum + ": currentSemester must be at least 1.");
        }
        CohortInfo cohort = resolveCohort(row, code, rowNum, refs.cohorts());
        String enrollCode = upper(row.enrollmentSemester());
        String enrollId = enrollCode.isEmpty() ? null : refs.semesters().get(enrollCode);
        if (!enrollCode.isEmpty() && enrollId == null) {
            throw new IllegalArgumentException("Row " + rowNum + ": Enrollment semester '" + enrollCode + "' was not found.");
        }
        String expectedGradId = null;
        if (cohort != null && enrollId != null && cohort.maxTerms() > 0) {
            String gradCode = ExpectedGraduation.code(enrollCode, refs.currentSemesterCode(), currentSem, cohort.maxTerms());
            if (gradCode != null) {
                expectedGradId = refs.semesters().get(gradCode.toUpperCase(Locale.ROOT));
            }
        }
        refs.studentCodes().add(code);
        return new Object[] {
                userId.toString(), code, cohort == null ? null : cohort.majorId(),
                cohort == null ? null : cohort.id(), enrollId, expectedGradId, currentSem
        };
    }

    private CohortInfo resolveCohort(UserImportRow row, String studentCode, int rowNum, Map<String, CohortInfo> cohorts) {
        String cohortVersion = upper(row.cohort());
        if (cohortVersion.isEmpty()) {
            return null;
        }
        String majorCode = upper(row.majorCode());
        if (majorCode.isEmpty() && studentCode.length() >= 2) {
            majorCode = studentCode.substring(0, 2);
        }
        CohortInfo info = cohorts.get(majorCode + "|" + cohortVersion);
        if (info == null) {
            throw new IllegalArgumentException("Row " + rowNum + ": Cohort '" + majorCode + " " + cohortVersion + "' was not found.");
        }
        if (!"ACTIVE".equalsIgnoreCase(info.majorStatus()) || !"ACTIVE".equalsIgnoreCase(info.status())) {
            throw new IllegalArgumentException("Row " + rowNum + ": Cohort '" + majorCode + " " + cohortVersion + "' is not active.");
        }
        StudentCodeValidator.validateMajorAndCohortMatch(studentCode, info.majorCode(), info.version());
        return info;
    }

    private Object[] buildAdvisorRow(UUID userId, String email, Integer requestedMax, int rowNum) {
        int maxAdvisees = requestedMax == null ? DEFAULT_MAX_ADVISEES : requestedMax;
        if (maxAdvisees < 1) {
            throw new IllegalArgumentException("Row " + rowNum + ": maxAdvisees must be at least 1.");
        }
        int at = email.indexOf('@');
        String advisorCode = at > 0 ? email.substring(0, at).strip() : "";
        if (advisorCode.isEmpty() || advisorCode.length() > 50) {
            throw new IllegalArgumentException("Row " + rowNum + ": Advisor email prefix must be 1 to 50 characters.");
        }
        return new Object[] {userId.toString(), advisorCode, maxAdvisees};
    }

    private void batchInsert(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }
    }

    private static String normalizeFullName(String value, int rowNum) {
        String clean = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 255) {
            throw new IllegalArgumentException("Row " + rowNum + ": fullName is required and must be at most 255 characters.");
        }
        return clean;
    }

    private static String normalizePhone(String phone, int rowNum) {
        String clean = phone == null || phone.isBlank() ? null : phone.strip();
        if (clean != null && !StudentOnboardingService.PHONE.matcher(clean).matches()) {
            throw new IllegalArgumentException("Row " + rowNum + ": phone must be a 10-digit Vietnamese number starting with 0.");
        }
        return clean;
    }

    private static String upper(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
    }
}
