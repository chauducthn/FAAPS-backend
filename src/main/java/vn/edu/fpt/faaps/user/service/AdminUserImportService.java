package vn.edu.fpt.faaps.user.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.academic.service.StudentOnboardingService;
import vn.edu.fpt.faaps.auth.service.AuthService;
import vn.edu.fpt.faaps.common.validation.IdentityInput;
import vn.edu.fpt.faaps.common.validation.StudentCodeValidator;
import vn.edu.fpt.faaps.user.service.AdminUserImportLoader.CohortInfo;
import vn.edu.fpt.faaps.user.service.AdminUserImportLoader.LoadedRefs;

/**
 * Atomic batch import of student and advisor accounts from Excel spreadsheets.
 */
@Service
public class AdminUserImportService {

    private static final int BATCH_SIZE = 500;
    private static final int DEFAULT_MAX_ADVISEES = 30;

    private final JdbcTemplate jdbc;

    public AdminUserImportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record UserImportRow(String role, String email, String fullName, String phone, String studentCode,
            String majorCode, String cohort, String enrollmentSemester, Integer currentSemester, Integer maxAdvisees) {}
    public record ImportUsersRequest(List<UserImportRow> items) {}
    public record ImportUsersSummary(int created, int studentsCreated, int advisorsCreated, int skipped) {}

    @Transactional
    public ImportUsersSummary importBatch(ImportUsersRequest request) {
        List<UserImportRow> items = request == null || request.items() == null ? List.of() : request.items();
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No users provided for import.");
        }
        LoadedRefs refs = AdminUserImportLoader.preload(jdbc);
        String defaultHash = new BCryptPasswordEncoder().encode(AuthService.DEFAULT_INITIAL_PASSWORD);
        List<Object[]> userRows = new ArrayList<>();
        List<Object[]> studentRows = new ArrayList<>();
        List<Object[]> advisorRows = new ArrayList<>();
        List<Object[]> advisorCohortRows = new ArrayList<>();
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
                CohortInfo advCohort = resolveActiveCohort(row, refs.cohorts());
                if (advCohort != null) {
                    advisorCohortRows.add(new Object[] { advCohort.id(), userId.toString() });
                }
            }
            refs.emails().add(email);
            userRows.add(new Object[] { userId.toString(), roleId, email, defaultHash, fullName, phone });
        }

        batchInsert(AdminUserImportLoader.INSERT_USER_SQL, userRows);
        batchInsert(AdminUserImportLoader.INSERT_STUDENT_SQL, studentRows);
        batchInsert(AdminUserImportLoader.INSERT_ADVISOR_SQL, advisorRows);
        batchInsert(AdminUserImportLoader.INSERT_COHORT_ADVISOR_SQL, advisorCohortRows);
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
        CohortInfo cohort = resolveCohort(row, code, refs.cohorts());
        String majorCode = upper(row.majorCode());
        String majorId = cohort != null ? cohort.majorId() : refs.majorIds().get(majorCode);
        String enrollCode = upper(row.enrollmentSemester());
        String enrollId = enrollCode.isEmpty() ? null
                : AdminUserImportLoader.ensureSemester(jdbc, refs.semesters(), enrollCode);
        if (!enrollCode.isEmpty() && enrollId == null) {
            throw new IllegalArgumentException(
                    "Row " + rowNum + ": Enrollment semester '" + enrollCode + "' must use SP/SU/FA + year.");
        }
        String expectedGradId = null;
        if (cohort != null && enrollId != null && cohort.maxTerms() > 0) {
            String gradCode = ExpectedGraduation.code(enrollCode, refs.currentSemesterCode(), currentSem,
                    cohort.maxTerms());
            if (gradCode != null) {
                expectedGradId = AdminUserImportLoader.ensureSemester(jdbc, refs.semesters(), gradCode);
            }
        }
        refs.studentCodes().add(code);
        return new Object[] {
                userId.toString(), code, majorId,
                cohort == null ? null : cohort.id(), enrollId, expectedGradId, currentSem
        };
    }

    private CohortInfo resolveCohort(UserImportRow row, String studentCode, Map<String, CohortInfo> cohorts) {
        CohortInfo info = resolveActiveCohort(row, cohorts);
        if (info == null)
            return null;
        StudentCodeValidator.validateMajorAndCohortMatch(studentCode, info.majorCode(), info.version());
        return info;
    }

    private CohortInfo resolveActiveCohort(UserImportRow row, Map<String, CohortInfo> cohorts) {
        String majorCode = upper(row.majorCode());
        String cohortVersion = upper(row.cohort());
        if (majorCode.isEmpty() || cohortVersion.isEmpty())
            return null;
        CohortInfo info = cohorts.get(majorCode + "|" + cohortVersion);
        return (info != null && "ACTIVE".equalsIgnoreCase(info.majorStatus())
                && "ACTIVE".equalsIgnoreCase(info.status()))
                        ? info
                        : null;
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
        return new Object[] { userId.toString(), advisorCode, maxAdvisees };
    }

    private void batchInsert(String sql, List<Object[]> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            jdbc.batchUpdate(sql, rows.subList(i, Math.min(i + BATCH_SIZE, rows.size())));
        }
    }

    private static String normalizeFullName(String value, int rowNum) {
        return AdminUserImportLoader.normalizeFullName(value, rowNum);
    }

    private static String normalizePhone(String phone, int rowNum) {
        return AdminUserImportLoader.normalizePhone(phone, rowNum);
    }

    private static String upper(String value) {
        return AdminUserImportLoader.upper(value);
    }
}
