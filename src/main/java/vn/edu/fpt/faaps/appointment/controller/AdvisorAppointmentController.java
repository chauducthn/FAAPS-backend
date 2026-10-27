package vn.edu.fpt.faaps.appointment.controller;

import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos;
import vn.edu.fpt.faaps.appointment.service.AppointmentService;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AdvisorAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.UpdateAppointmentStatusDto;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/advisor/appointments", "/advisor/appointments"})
public class AdvisorAppointmentController {

    private final AppointmentService appointmentService;

    public AdvisorAppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    public List<AdvisorAppointmentDto> getAppointments(
            @AuthenticationPrincipal AuthSession advisor,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return appointmentService.getAdvisorAppointments(advisor.id(), from, to);
    }

    @GetMapping("/{id}")
    public BookedAppointmentDto getAppointment(@PathVariable UUID id,
            @AuthenticationPrincipal AuthSession advisor) {
        return appointmentService.getAdvisorAppointment(id, advisor.id());
    }

    @PutMapping("/{id}/status")
    public BookedAppointmentDto updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateAppointmentStatusDto body,
            @AuthenticationPrincipal AuthSession advisor) {
        return appointmentService.updateAppointmentStatus(id, advisor.id(), body);
    }
}
