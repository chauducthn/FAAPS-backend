package vn.edu.fpt.faaps.appointment.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AdvisorAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AvailabilitySlotDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AvailableSlotDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.CancelAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.ConfigureAvailabilityDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.RescheduleAppointmentDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.UpdateAppointmentStatusDto;
import vn.edu.fpt.faaps.appointment.repository.AppointmentReadRepository;

@Service
public class AppointmentService {
    private final AppointmentReadRepository reads;
    private final AppointmentAvailabilityHelper availability;
    private final AppointmentBookingHelper booking;
    private final AppointmentStatusHelper statusHelper;

    public AppointmentService(JdbcTemplate jdbc) {
        this.reads = new AppointmentReadRepository(jdbc);
        this.availability = new AppointmentAvailabilityHelper(jdbc, this.reads);
        this.booking = new AppointmentBookingHelper(jdbc, this.reads);
        this.statusHelper = new AppointmentStatusHelper(jdbc, this.reads, this.booking);
    }

    public List<AvailabilitySlotDto> getAdvisorAvailability(UUID advisorId) {
        return reads.availability(advisorId);
    }

    @Transactional
    public void configureAdvisorAvailability(UUID advisorId, ConfigureAvailabilityDto body) {
        availability.configureAdvisorAvailability(advisorId, body);
    }

    public List<AvailableSlotDto> getAvailableSlots(LocalDate date, UUID requestedAdvisorId, UUID studentId) {
        return availability.getAvailableSlots(date, requestedAdvisorId, studentId);
    }

    @Transactional
    public BookedAppointmentDto bookAppointment(UUID studentId, BookAppointmentDto input) {
        return booking.bookAppointment(studentId, input);
    }

    @Transactional
    public BookedAppointmentDto updateAppointmentStatus(UUID appointmentId, UUID advisorId,
            UpdateAppointmentStatusDto body) {
        return statusHelper.updateAppointmentStatus(appointmentId, advisorId, body);
    }

    public List<AdvisorAppointmentDto> getAdvisorAppointments(UUID advisorId, String fromStr, String toStr) {
        return getAdvisorAppointments(advisorId, fromStr, toStr, null);
    }

    public List<AdvisorAppointmentDto> getAdvisorAppointments(UUID advisorId, String fromStr, String toStr,
            String status) {
        LocalDateTime from = fromStr != null && !fromStr.isBlank()
                ? booking.parseRangeBoundary(fromStr, false) : LocalDateTime.now().minusDays(30);
        LocalDateTime to = toStr != null && !toStr.isBlank()
                ? booking.parseRangeBoundary(toStr, true) : LocalDateTime.now().plusDays(60);
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The 'to' date must not be before 'from'");
        }
        List<AdvisorAppointmentDto> items = reads.advisorAppointments(advisorId, from, to);
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.strip())) {
            return items;
        }
        String target = status.strip().toUpperCase(Locale.ROOT);
        return items.stream().filter(a -> a.status() != null && a.status().equalsIgnoreCase(target)).toList();
    }

    public List<BookedAppointmentDto> getStudentAppointments(UUID studentId) {
        return reads.studentAppointments(studentId);
    }

    public BookedAppointmentDto getStudentAppointment(UUID appointmentId, UUID studentId) {
        BookedAppointmentDto appointment = getBookedAppointment(appointmentId);
        if (!appointment.studentId().equals(studentId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
        }
        return appointment;
    }

    public BookedAppointmentDto getAdvisorAppointment(UUID appointmentId, UUID advisorId) {
        BookedAppointmentDto appointment = getBookedAppointment(appointmentId);
        if (!appointment.advisorId().equals(advisorId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Appointment not found");
        }
        return appointment;
    }

    @Transactional
    public BookedAppointmentDto cancelAppointment(UUID appointmentId, UUID actorId, CancelAppointmentDto input) {
        return statusHelper.cancelAppointment(appointmentId, actorId, input);
    }

    @Transactional
    public BookedAppointmentDto rescheduleAppointment(UUID appointmentId, UUID actorId,
            RescheduleAppointmentDto input) {
        return booking.rescheduleAppointment(appointmentId, actorId, input);
    }

    public BookedAppointmentDto getBookedAppointment(UUID appointmentId) {
        return reads.appointment(appointmentId);
    }
}
