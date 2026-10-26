package vn.edu.fpt.faaps.appointment;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.AvailableSlotDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.CancelAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.RescheduleAppointmentDto;
import vn.edu.fpt.faaps.auth.AuthSession;

@RestController
@RequestMapping({"/api/appointments", "/appointments"})
public class AppointmentSlotsController {

    private final AppointmentService appointmentService;

    public AppointmentSlotsController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping("/available-slots")
    public List<AvailableSlotDto> getAvailableSlots(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID advisorId,
            @AuthenticationPrincipal AuthSession session) {
        return appointmentService.getAvailableSlots(date, advisorId, session != null ? session.id() : null);
    }

    @PutMapping("/{id}/cancel")
    public BookedAppointmentDto cancelAppointment(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelAppointmentDto body,
            @AuthenticationPrincipal AuthSession session) {
        return appointmentService.cancelAppointment(id, session.id(), body);
    }

    @PutMapping("/{id}/reschedule")
    public BookedAppointmentDto rescheduleAppointment(
            @PathVariable UUID id,
            @Valid @RequestBody RescheduleAppointmentDto body,
            @AuthenticationPrincipal AuthSession session) {
        return appointmentService.rescheduleAppointment(id, session.id(), body);
    }
}
