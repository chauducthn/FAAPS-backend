package vn.edu.fpt.faaps.appointment;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.auth.AuthSession;

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
