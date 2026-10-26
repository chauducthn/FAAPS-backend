package vn.edu.fpt.faaps.user.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public final class AdminUserDtos {
    private AdminUserDtos() {}

    public record Summary(UUID id, String userId, String fullName, String email,
            String role, String status, LocalDateTime createdAt) {}

    public record Detail(UUID id, String userId, String fullName, String email,
            String role, String status, LocalDateTime createdAt, LocalDateTime updatedAt,
            String majorId, String majorName, String curriculumVersionId, String curriculumCode,
            String enrollmentSemesterId, String expectedGraduationSemesterId,
            Integer currentSemester, String academicStatus) {}

    public record CreateAccount(String role, String email, String fullName, String phone,
            String studentCode, UUID curriculumVersionId, UUID enrollmentSemesterId,
            UUID expectedGraduationSemesterId, Integer currentSemester, Integer maxAdvisees) {}

    public record ChangeRole(String role, String studentCode, UUID curriculumVersionId,
            UUID enrollmentSemesterId, Integer maxAdvisees) {}

    public record Page(List<Summary> items, int page, int size, long totalElements, long totalPages) {}
}
