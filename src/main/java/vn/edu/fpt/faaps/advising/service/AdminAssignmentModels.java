package vn.edu.fpt.faaps.advising.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface AdminAssignmentModels {
    record Advisor(UUID id, String email, String fullName, Integer maxAdvisees, long activeStudents) {
    }

    record Cohort(UUID cohortId, String cohort, String majorCode, long students) {
    }

    record Student(UUID id, String studentCode, String fullName, UUID cohortId, String cohort,
            String majorCode, String advisorId, String advisorName, LocalDate assignedDate) {
    }

    record AdvisorDetail(Advisor advisor, String email, String status, List<Student> students,
            List<Cohort> cohorts, List<Cohort> configuredCohorts) {
    }

    record History(String id, String advisorId, String advisorEmail, String advisorName,
            LocalDate assignedDate, LocalDateTime assignedAt, String assignedByUserId, String assignedByName,
            String notes, LocalDate endDate, String status) {
    }

    record Page<T>(List<T> items, int page, int size, long totalElements) {
        public long totalPages() {
            return (totalElements + size - 1) / size;
        }
    }

    class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    class AssignmentError extends RuntimeException {
        private final String code;

        public AssignmentError(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    record Preview(String cohort, int unassignedStudents, Map<UUID, Integer> allocation) {
    }

    record Result(int assigned, Map<UUID, Integer> allocation) {
    }

    class Conflict extends RuntimeException {
    }

    record AppointmentImpact(UUID appointmentId, UUID studentId, UUID currentAdvisorId,
            LocalDateTime scheduledAt) {
    }

    class AppointmentWarningRequired extends RuntimeException {
        private final List<AppointmentImpact> appointments;

        public AppointmentWarningRequired(List<AppointmentImpact> appointments) {
            this.appointments = appointments;
        }

        public List<AppointmentImpact> appointments() {
            return appointments;
        }
    }
}
