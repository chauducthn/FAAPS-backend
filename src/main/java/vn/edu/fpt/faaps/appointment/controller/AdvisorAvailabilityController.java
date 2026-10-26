package vn.edu.fpt.faaps.appointment.controller;

import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos;
import vn.edu.fpt.faaps.appointment.service.AppointmentService;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AvailabilitySlotDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.ConfigureAvailabilityDto;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/advisor/availability", "/advisor/availability"})
public class AdvisorAvailabilityController {

    private final AppointmentService appointmentService;

    public AdvisorAvailabilityController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @GetMapping
    public List<AvailabilitySlotDto> getAvailability(@AuthenticationPrincipal AuthSession advisor) {
        return appointmentService.getAdvisorAvailability(advisor.id());
    }

    @PostMapping
    public ResponseEntity<Void> configureAvailability(@AuthenticationPrincipal AuthSession advisor,
                                                      @Valid @RequestBody ConfigureAvailabilityDto body) {
        appointmentService.configureAdvisorAvailability(advisor.id(), body);
        return ResponseEntity.ok().build();
    }
}
