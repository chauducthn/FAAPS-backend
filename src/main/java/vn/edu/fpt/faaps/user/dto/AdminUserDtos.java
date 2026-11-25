package vn.edu.fpt.faaps.user.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminUserDtos {
    private AdminUserDtos() {}

    /** {@code majorCode} and {@code cohort} (the curriculum version, e.g. K19) are set for students only. */
    public record Summary(UUID id, String userId, String fullName, String email,
            String role, String status, LocalDateTime createdAt, String majorCode, String cohort) {}

    public record Detail(UUID id, String userId, String fullName, String email,
            String role, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            String majorId, String majorName, String curriculumVersionId, String curriculumCode,
            String enrollmentSemesterId, String expectedGraduationSemesterId,
            Integer currentSemester, String academicStatus) {}

    public record CreateAccount(String role, String email, String fullName, String phone,
            String studentCode, String advisorCode, UUID curriculumVersionId, UUID enrollmentSemesterId,
            UUID expectedGraduationSemesterId, Integer currentSemester, Integer maxAdvisees) {
        public CreateAccount(String role, String email, String fullName, String phone,
                String studentCode, UUID curriculumVersionId, UUID enrollmentSemesterId,
                UUID expectedGraduationSemesterId, Integer currentSemester, Integer maxAdvisees) {
            this(role, email, fullName, phone, studentCode, null, curriculumVersionId,
                    enrollmentSemesterId, expectedGraduationSemesterId, currentSemester, maxAdvisees);
        }
    }

    public record ChangeRole(String role, String email, String studentCode, String advisorCode,
            UUID curriculumVersionId, UUID enrollmentSemesterId, Integer maxAdvisees) {
        public ChangeRole(String role, String studentCode, String advisorCode, UUID curriculumVersionId,
                UUID enrollmentSemesterId, Integer maxAdvisees) {
            this(role, null, studentCode, advisorCode, curriculumVersionId, enrollmentSemesterId, maxAdvisees);
        }

        public ChangeRole(String role, String studentCode, UUID curriculumVersionId,
                UUID enrollmentSemesterId, Integer maxAdvisees) {
            this(role, null, studentCode, null, curriculumVersionId, enrollmentSemesterId, maxAdvisees);
        }
    }

    public record BulkRoleItem(UUID userId, String email, String studentCode,
            UUID curriculumVersionId, UUID enrollmentSemesterId, Integer maxAdvisees) {}

    public record BulkRoleEntry(UUID userId, String email, String fullName, String status, String reason) {}

    public record BulkRoleResponse(int updated, List<BulkRoleEntry> results) {}

    public record EmailCheckItem(UUID userId, String email) {}

    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {}

    public record PasswordResetEntry(String userId, String email, String fullName,
            String status, String newPassword, String reason) {}

    public record PasswordResetResponse(int updated, String defaultPassword,
            List<PasswordResetEntry> resetResults) {}

    public record PasswordResetResult(int updated, String defaultPassword,
            List<PasswordResetEntry> resetResults) {}

    public record BulkResetPassword(List<UUID> userIds) {}
}
