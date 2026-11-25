package vn.edu.fpt.faaps.appointment.service;

import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AvailabilitySlotDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.AvailableSlotDto;
import vn.edu.fpt.faaps.appointment.dto.AppointmentDtos.ConfigureAvailabilityDto;
import vn.edu.fpt.faaps.appointment.repository.AppointmentReadRepository;

class AppointmentAvailabilityHelper {
    private static final DateTimeFormatter ISO_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final AppointmentReadRepository reads;

    AppointmentAvailabilityHelper(JdbcTemplate jdbc, AppointmentReadRepository reads) {
        this.jdbc = jdbc;
        this.reads = reads;
    }

    void configureAdvisorAvailability(UUID advisorId, ConfigureAvailabilityDto body) {
        if (body.slots() == null) {
            throw new IllegalArgumentException("slots cannot be null");
        }
        for (AvailabilitySlotDto slot : body.slots()) {
            if (slot.dayOfWeek() < 1 || slot.dayOfWeek() > 7) {
                throw new IllegalArgumentException("dayOfWeek must be between 1 and 7");
            }
            LocalTime st = LocalTime.parse(slot.startTime(), TIME_FMT);
            LocalTime et = LocalTime.parse(slot.endTime(), TIME_FMT);
            if (!st.isBefore(et)) {
                throw new IllegalArgumentException("startTime must be before endTime");
            }
        }

        jdbc.update("DELETE FROM advisor_availability WHERE advisor_id = ?", advisorId.toString());

        for (AvailabilitySlotDto slot : body.slots()) {
            DayOfWeek dow = DayOfWeek.of(slot.dayOfWeek());
            LocalTime st = LocalTime.parse(slot.startTime(), TIME_FMT);
            LocalTime et = LocalTime.parse(slot.endTime(), TIME_FMT);
            jdbc.update("""
                    INSERT INTO advisor_availability (id, advisor_id, day_of_week, start_time, end_time, is_active)
                    VALUES (?, ?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(), advisorId.toString(), dow.name(),
                    Time.valueOf(st), Time.valueOf(et));
        }
    }

    List<AvailableSlotDto> getAvailableSlots(LocalDate date, UUID requestedAdvisorId, UUID studentId) {
        UUID advisorId = requestedAdvisorId != null ? requestedAdvisorId : reads.assignedAdvisor(studentId);
        if (advisorId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No advisor assigned. Please specify an advisor.");
        }

        int isoDay = date.getDayOfWeek().getValue();
        String dayName = DayOfWeek.of(isoDay).name();
        String dayNum = String.valueOf(isoDay);

        List<Map<String, Object>> windows = jdbc.queryForList("""
                SELECT start_time, end_time
                FROM advisor_availability
                WHERE advisor_id = ? AND is_active = TRUE AND (day_of_week = ? OR day_of_week = ?)
                ORDER BY start_time
                """, advisorId.toString(), dayName, dayNum);

        if (windows.isEmpty()) {
            return List.of();
        }

        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        List<BookedInterval> existingSessions = jdbc.query("""
                SELECT scheduled_at, duration_minutes, ended_at
                FROM advising_sessions
                WHERE advisor_id = ?
                  AND status IN ('PENDING', 'ACCEPTED', 'SCHEDULED', 'IN_PROGRESS')
                  AND scheduled_at >= ? AND scheduled_at < ?
                """, (rs, rowNum) -> {
            LocalDateTime start = rs.getTimestamp("scheduled_at").toLocalDateTime();
            int duration = rs.getInt("duration_minutes");
            if (duration <= 0) {
                duration = 30;
            }
            Timestamp endedTs = rs.getTimestamp("ended_at");
            LocalDateTime end = endedTs != null ? endedTs.toLocalDateTime() : start.plusMinutes(duration);
            return new BookedInterval(start, end);
        }, advisorId.toString(), Timestamp.valueOf(dayStart), Timestamp.valueOf(dayEnd));

        List<AvailableSlotDto> availableSlots = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (Map<String, Object> window : windows) {
            LocalTime windowStart = ((Time) window.get("start_time")).toLocalTime();
            LocalTime windowEnd = ((Time) window.get("end_time")).toLocalTime();
            LocalTime current = windowStart;
            while (!current.plusMinutes(30).isAfter(windowEnd)) {
                LocalDateTime slotStart = LocalDateTime.of(date, current);
                LocalDateTime slotEnd = slotStart.plusMinutes(30);
                if (slotStart.isAfter(now)) {
                    boolean collides = existingSessions.stream()
                            .anyMatch(b -> slotStart.isBefore(b.end()) && slotEnd.isAfter(b.start()));
                    if (!collides) {
                        availableSlots.add(new AvailableSlotDto(slotStart.format(ISO_DT), slotEnd.format(ISO_DT)));
                    }
                }
                current = current.plusMinutes(30);
            }
        }
        return availableSlots;
    }

    private record BookedInterval(LocalDateTime start, LocalDateTime end) {}
}
