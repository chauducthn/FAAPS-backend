package vn.edu.fpt.faaps.appointment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public class AppointmentDtos {

    public record AvailabilitySlotDto(
            @Min(1) @Max(7) int dayOfWeek,
            @NotBlank String startTime,
            @NotBlank String endTime
    ) {}

    public record ConfigureAvailabilityDto(
            @NotNull List<AvailabilitySlotDto> slots
    ) {}

    public record AvailableSlotDto(
            String startTime,
            String endTime
    ) {}

    public record AdvisorOptionDto(
            String id,
            String fullName,
            String email,
            boolean assigned
    ) {}

    public record BookAppointmentDto(
            UUID advisorId,
            @NotBlank String startTime,
            @NotBlank String reason,
            UUID advisingRequestId
    ) {
        public BookAppointmentDto(UUID advisorId, String startTime, String reason) {
            this(advisorId, startTime, reason, null);
        }
    }

    public record UpdateAppointmentStatusDto(
            @NotBlank String status,
            String reason,
            String consultationSummary
    ) {
        public UpdateAppointmentStatusDto(String status, String reason) {
            this(status, reason, null);
        }
    }

    public record RescheduleAppointmentDto(
            @NotBlank String startTime,
            String reason
    ) {}

    public record CancelAppointmentDto(
            String reason
    ) {}

    public record AdvisorAppointmentDto(
            String id,
            String studentId,
            String studentName,
            String studentCode,
            String advisingRequestId,
            String startTime,
            String endTime,
            String reason,
            String status,
            String decisionNote,
            String consultationSummary,
            String createdAt
    ) {
        public AdvisorAppointmentDto(String id, String studentId, String studentName, String studentCode,
                String startTime, String endTime, String reason, String status, String decisionNote,
                String consultationSummary, String createdAt) {
            this(id, studentId, studentName, studentCode, null, startTime, endTime, reason, status,
                    decisionNote, consultationSummary, createdAt);
        }

        public AdvisorAppointmentDto(String id, String studentId, String studentName, String studentCode,
                String startTime, String endTime, String reason, String status, String decisionNote, String createdAt) {
            this(id, studentId, studentName, studentCode, null, startTime, endTime, reason, status, decisionNote,
                    "COMPLETED".equalsIgnoreCase(status) ? decisionNote : null, createdAt);
        }
    }

    public record BookedAppointmentDto(
            String id,
            String studentId,
            String studentName,
            String studentCode,
            String advisorId,
            String advisorName,
            String advisingRequestId,
            String startTime,
            String endTime,
            String reason,
            String status,
            String decisionNote,
            String consultationSummary,
            String createdAt
    ) {
        public BookedAppointmentDto(String id, String studentId, String studentName, String studentCode,
                String advisorId, String advisorName, String startTime, String endTime, String reason,
                String status, String decisionNote, String consultationSummary, String createdAt) {
            this(id, studentId, studentName, studentCode, advisorId, advisorName, null, startTime, endTime,
                    reason, status, decisionNote, consultationSummary, createdAt);
        }

        public BookedAppointmentDto(String id, String studentId, String studentName, String studentCode,
                String advisorId, String advisorName, String startTime, String endTime, String reason,
                String status, String decisionNote, String createdAt) {
            this(id, studentId, studentName, studentCode, advisorId, advisorName, null, startTime, endTime, reason,
                    status, decisionNote, "COMPLETED".equalsIgnoreCase(status) ? decisionNote : null, createdAt);
        }
    }
}
