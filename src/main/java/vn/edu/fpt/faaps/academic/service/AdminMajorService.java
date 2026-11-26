package vn.edu.fpt.faaps.academic.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminMajorService {
    private final JdbcTemplate jdbc;
    private final AdminMajorQueryHelper queryHelper;
    private final AdminMajorCohortHelper cohortHelper;

    public AdminMajorService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.queryHelper = new AdminMajorQueryHelper(jdbc);
        this.cohortHelper = new AdminMajorCohortHelper(jdbc);
    }

    public Page list(String cohort, String status, String search, int page, int size, String sort) {
        return queryHelper.list(null, null, cohort, status, search, page, size, sort);
    }

    public Page list(String code, BigDecimal durationYears, String cohort, String status,
            String search, int page, int size, String sort) {
        return queryHelper.list(code, durationYears, cohort, status, search, page, size, sort);
    }

    public MajorItem detail(UUID id) {
        return queryHelper.detail(id);
    }

    @Transactional
    public MajorItem create(String code, String name, String description, int totalCredits,
            BigDecimal durationYears, String status, String cohort) {
        if (code == null || code.isBlank() || name == null || name.isBlank()) {
            throw new IllegalArgumentException("code and name are required");
        }
        if (totalCredits <= 0) {
            throw new IllegalArgumentException("totalCredits must be positive");
        }
        if (durationYears == null || durationYears.compareTo(BigDecimal.ZERO) <= 0) {
            durationYears = new BigDecimal("4.0");
        }
        String majorStatus = (status == null || status.isBlank()) ? "ACTIVE" : status.toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE", "DRAFT").contains(majorStatus)) {
            throw new IllegalArgumentException("status is invalid");
        }

        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO majors (id, code, name, description, total_credits, duration_years,
                        status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, id.toString(), code.strip().toUpperCase(), name.strip(), description,
                    totalCredits, durationYears, majorStatus);
        } catch (DuplicateKeyException e) {
            throw new Conflict("Major with this code and name already exists");
        }

        if (cohort != null && !cohort.isBlank()) {
            cohortHelper.insertInitialCohort(id, name.strip(), cohort.strip());
        }
        return detail(id);
    }

    @Transactional
    public MajorItem update(UUID id, String code, String name, String description,
            Integer totalCredits, BigDecimal durationYears, String status) {
        return update(id, code, name, description, totalCredits, durationYears, status, null);
    }

    @Transactional
    public MajorItem update(UUID id, String code, String name, String description,
            Integer totalCredits, BigDecimal durationYears, String status, String cohort) {
        MajorItem current = detail(id);
        String newCode = (code != null && !code.isBlank()) ? code.strip().toUpperCase() : current.code();
        String newName = (name != null && !name.isBlank()) ? name.strip() : current.name();
        String newDesc = description != null ? description : current.description();
        int newCredits = (totalCredits != null && totalCredits > 0) ? totalCredits : current.totalCredits();
        BigDecimal newDuration = (durationYears != null && durationYears.compareTo(BigDecimal.ZERO) > 0)
                ? durationYears : current.durationYears();
        String newStatus = current.status();
        if (status != null && !status.isBlank()) {
            String upper = status.toUpperCase();
            if (List.of("ACTIVE", "INACTIVE", "DRAFT").contains(upper)) {
                newStatus = upper;
            }
        }

        try {
            jdbc.update("""
                    UPDATE majors
                    SET code = ?, name = ?, description = ?, total_credits = ?, duration_years = ?,
                        status = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, newCode, newName, newDesc, newCredits, newDuration, newStatus, id.toString());
        } catch (DuplicateKeyException e) {
            throw new Conflict("Major with this code and name already exists");
        }

        if (cohort != null && !cohort.isBlank()) {
            cohortHelper.upsertCohortForMajor(id, newName, cohort.strip());
        }
        return detail(id);
    }

    @Transactional
    public void delete(UUID id) {
        detail(id);
        try {
            Integer studentCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM students WHERE major_id = ?", Integer.class, id.toString());
            if (studentCount != null && studentCount > 0) {
                throw new Conflict("Cannot delete major because " + studentCount + " student(s) are assigned to it");
            }
            Integer curriculumCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM curriculum_versions WHERE major_id = ?", Integer.class, id.toString());
            if (curriculumCount != null && curriculumCount > 0) {
                throw new Conflict("Cannot delete major because "
                        + curriculumCount + " curriculum version(s) reference it");
            }
            jdbc.update("DELETE FROM majors WHERE id = ?", id.toString());
        } catch (Conflict c) {
            throw c;
        } catch (DataIntegrityViolationException e) {
            throw new Conflict("Cannot delete major due to existing references");
        }
    }

    @Transactional
    public int bulkDelete(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int deleted = 0;
        for (UUID id : ids) {
            delete(id);
            deleted++;
        }
        return deleted;
    }

    public record MajorItem(UUID id, String code, String name, String description, int totalCredits,
            BigDecimal durationYears, String status, String cohort, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record PageData(List<MajorItem> items, int page, int pageSize, long totalItems, long totalPages) {
    }

    public record Page(List<MajorItem> items, int page, int size, int pageSize, long totalElements,
            long totalItems, long totalPages, PageData data, boolean success, String message) {
    }

    public static class NotFound extends RuntimeException {
    }

    public static class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }
}
