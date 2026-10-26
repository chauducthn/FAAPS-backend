package vn.edu.fpt.faaps.appointment.controller;

import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos;
import vn.edu.fpt.faaps.appointment.service.AppointmentService;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/student/appointments", "/student/appointments"})
public class StudentAppointmentController {

    private final AppointmentService appointmentService;

    public StudentAppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @PostMapping
    public BookedAppointmentDto bookAppointment(
            @AuthenticationPrincipal AuthSession student,
            @Valid @RequestBody BookAppointmentDto body) {
        return appointmentService.bookAppointment(student.id(), body);
    }

    @GetMapping
    public List<BookedAppointmentDto> getStudentAppointments(
            @AuthenticationPrincipal AuthSession student) {
        return appointmentService.getStudentAppointments(student.id());
    }
}
