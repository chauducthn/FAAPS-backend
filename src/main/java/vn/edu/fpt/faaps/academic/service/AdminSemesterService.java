package vn.edu.fpt.faaps.academic.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminSemesterService {
    private final JdbcTemplate jdbc;

    public AdminSemesterService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<SemesterItem> list() {
        return jdbc.query("""
                SELECT id, code, name, start_date, end_date FROM semesters
                ORDER BY start_date DESC, code DESC
                """, (rs, row) -> new SemesterItem(UUID.fromString(rs.getString("id")),
                rs.getString("code"), rs.getString("name"),
                rs.getDate("start_date").toLocalDate(), rs.getDate("end_date").toLocalDate()));
    }

    public SemesterItem create(SemesterInput input) {
        validate(input);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO semesters (id, code, name, start_date, end_date, is_current) VALUES (?, ?, ?, ?, ?, FALSE)",
                    id.toString(), input.code().strip().toUpperCase(), input.name().strip(),
                    input.startDate(), input.endDate());
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Semester code already exists");
        }
        return get(id);
    }

    public SemesterItem update(UUID id, SemesterInput input) {
        validate(input);
        try {
            int count = jdbc.update("""
                    UPDATE semesters SET code=?, name=?, start_date=?, end_date=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """, input.code().strip().toUpperCase(), input.name().strip(),
                    input.startDate(), input.endDate(), id.toString());
            if (count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Semester not found");
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Semester code already exists");
        }
        return get(id);
    }

    private SemesterItem get(UUID id) {
        return list().stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Semester not found"));
    }

    private void validate(SemesterInput input) {
        if (input.code() == null || !input.code().strip().matches("(?i)(SP|SU|FA)[0-9]{4}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Semester code must look like FA2026, SP2026 or SU2026");
        }
        if (input.name() == null || input.name().isBlank() || input.name().length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Semester name is required (maximum 100 characters)");
        }
        if (input.startDate() == null || input.endDate() == null || !input.endDate().isAfter(input.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "End date must be after start date");
        }
    }

    public record SemesterInput(String code, String name, LocalDate startDate, LocalDate endDate) {}
    public record SemesterItem(UUID id, String code, String name, LocalDate startDate, LocalDate endDate) {}
}
