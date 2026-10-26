package vn.edu.fpt.faaps.appointment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.AvailabilitySlotDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.AvailableSlotDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.BookedAppointmentDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.ConfigureAvailabilityDto;
import vn.edu.fpt.faaps.appointment.AppointmentDtos.UpdateAppointmentStatusDto;

class AppointmentServiceTest {

    private JdbcTemplate jdbc;
    private AppointmentService appointmentService;
    private UUID advisorId;
    private UUID studentId;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:appointments-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("CREATE TABLE users (id VARCHAR(36) PRIMARY KEY, full_name VARCHAR(255))");
        jdbc.execute("CREATE TABLE students (user_id VARCHAR(36) PRIMARY KEY, student_code VARCHAR(50))");
        jdbc.execute("CREATE TABLE advisors (user_id VARCHAR(36) PRIMARY KEY)");
        jdbc.execute("CREATE TABLE student_advisor_assignments (student_id VARCHAR(36), advisor_id VARCHAR(36), status VARCHAR(20), end_date DATE)");
        jdbc.execute("CREATE TABLE notifications (id VARCHAR(36) PRIMARY KEY, recipient_user_id VARCHAR(36), notification_type VARCHAR(50), title VARCHAR(255), message TEXT, is_read BOOLEAN)");
        jdbc.execute("""
                CREATE TABLE advisor_availability (
                    id VARCHAR(36) PRIMARY KEY,
                    advisor_id VARCHAR(36),
                    day_of_week VARCHAR(20),
                    start_time TIME,
                    end_time TIME,
                    is_active BOOLEAN
                )
                """);
        jdbc.execute("""
                CREATE TABLE advising_sessions (
                    id VARCHAR(36) PRIMARY KEY,
                    student_id VARCHAR(36),
                    advisor_id VARCHAR(36),
                    session_type VARCHAR(20),
                    scheduled_at TIMESTAMP,
                    ended_at TIMESTAMP,
                    duration_minutes INT,
                    status VARCHAR(20),
                    agenda TEXT,
                    summary TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);

        advisorId = UUID.randomUUID();
        studentId = UUID.randomUUID();

        jdbc.update("INSERT INTO users VALUES (?, 'Advisor John')", advisorId.toString());
        jdbc.update("INSERT INTO advisors VALUES (?)", advisorId.toString());

        jdbc.update("INSERT INTO users VALUES (?, 'Student Alice')", studentId.toString());
        jdbc.update("INSERT INTO students VALUES (?, 'SE123456')", studentId.toString());
        jdbc.update("INSERT INTO student_advisor_assignments VALUES (?, ?, 'ACTIVE', NULL)",
                studentId.toString(), advisorId.toString());

        appointmentService = new AppointmentService(jdbc);
    }

    @Test
    void testConfigureAndGetAvailability() {
        AvailabilitySlotDto slot1 = new AvailabilitySlotDto(1, "08:30", "11:30");
        AvailabilitySlotDto slot2 = new AvailabilitySlotDto(3, "13:00", "16:00");
        appointmentService.configureAdvisorAvailability(advisorId, new ConfigureAvailabilityDto(List.of(slot1, slot2)));

        List<AvailabilitySlotDto> retrieved = appointmentService.getAdvisorAvailability(advisorId);
        assertEquals(2, retrieved.size());
    }

    @Test
    void testGetAvailableSlotsAndBookAppointment() {
        // Setup Monday availability
        LocalDate nextMonday = LocalDate.now().plusWeeks(1);
        while (nextMonday.getDayOfWeek().getValue() != 1) {
            nextMonday = nextMonday.plusDays(1);
        }

        AvailabilitySlotDto slot = new AvailabilitySlotDto(1, "09:00", "11:00");
        appointmentService.configureAdvisorAvailability(advisorId, new ConfigureAvailabilityDto(List.of(slot)));

        List<AvailableSlotDto> slots = appointmentService.getAvailableSlots(nextMonday, advisorId, studentId);
        assertEquals(4, slots.size()); // 09:00, 09:30, 10:00, 10:30

        String chosenSlot = slots.get(0).startTime();
        BookAppointmentDto bookInput = new BookAppointmentDto(advisorId, chosenSlot, "Academic guidance discussion");
        BookedAppointmentDto booked = appointmentService.bookAppointment(studentId, bookInput);

        assertNotNull(booked.id());
        assertEquals("PENDING", booked.status());
        assertEquals("Academic guidance discussion", booked.reason());

        // Verify that slot is no longer available
        List<AvailableSlotDto> remainingSlots = appointmentService.getAvailableSlots(nextMonday, advisorId, studentId);
        assertEquals(3, remainingSlots.size());
        assertFalse(remainingSlots.stream().anyMatch(s -> s.startTime().equals(chosenSlot)));

        // Advisor accepts appointment
        UpdateAppointmentStatusDto acceptInput = new UpdateAppointmentStatusDto("ACCEPTED", null);
        BookedAppointmentDto accepted = appointmentService.updateAppointmentStatus(UUID.fromString(booked.id()), advisorId, acceptInput);
        assertEquals("ACCEPTED", accepted.status());
    }
}
