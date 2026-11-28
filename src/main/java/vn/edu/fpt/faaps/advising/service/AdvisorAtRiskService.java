package vn.edu.fpt.faaps.advising.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.advising.service.AdvisorAtRiskQueryHelper.AdviseeRow;

@Service
public class AdvisorAtRiskService {
    private final AdvisorAtRiskQueryHelper queries;
    private final AdvisorAtRiskEvaluator evaluator;

    public AdvisorAtRiskService(JdbcTemplate jdbc) {
        this.queries = new AdvisorAtRiskQueryHelper(jdbc);
        this.evaluator = new AdvisorAtRiskEvaluator(this.queries);
    }

    public AtRiskStudentResponse list(UUID advisorId, String riskLevelFilter, String reasonFilter, int page, int size) {
        return list(advisorId, riskLevelFilter, reasonFilter, null, page, size);
    }

    public AtRiskStudentResponse list(UUID advisorId, String riskLevelFilter, String reasonFilter, UUID semesterId,
            int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Invalid page or size");
        }
        List<AtRiskStudent> filtered = computeAllAtRisk(advisorId, semesterId).stream()
                .filter(s -> riskLevelFilter == null || riskLevelFilter.isBlank()
                        || "ALL".equalsIgnoreCase(riskLevelFilter)
                        || s.riskLevel().equalsIgnoreCase(riskLevelFilter.strip()))
                .filter(s -> reasonFilter == null || reasonFilter.isBlank()
                        || "ALL".equalsIgnoreCase(reasonFilter)
                        || s.reasons().stream().anyMatch(r -> r.equalsIgnoreCase(reasonFilter.strip())))
                .toList();

        int total = filtered.size();
        int fromIndex = Math.min(page * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<AtRiskStudent> pageItems = filtered.subList(fromIndex, toIndex);
        int totalPages = (total + size - 1) / size;

        return new AtRiskStudentResponse(pageItems, total, page, size, totalPages);
    }

    public AtRiskCounts counts(UUID advisorId) {
        List<AtRiskStudent> all = computeAllAtRisk(advisorId);
        int high = 0;
        int medium = 0;
        int low = 0;
        for (AtRiskStudent s : all) {
            switch (s.riskLevel()) {
                case "HIGH" -> high++;
                case "MEDIUM" -> medium++;
                default -> low++;
            }
        }
        return new AtRiskCounts(high, medium, low, all.size());
    }

    @Transactional
    public AtRiskStudent acknowledge(UUID advisorId, UUID studentId, String note) {
        List<AdviseeRow> rows = queries.loadAdvisees(advisorId, studentId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Assigned student not found");
        }
        queries.acknowledgeWarnings(studentId, note);
        AtRiskStudent evaluated = evaluator.evaluate(rows.get(0));
        if (evaluated != null) {
            return evaluated;
        }
        AdviseeRow r = rows.get(0);
        return new AtRiskStudent(
                r.id(), r.studentCode(), r.fullName(), r.majorCode(), r.majorName(),
                r.currentSemester(), r.currentGpa() != null ? r.currentGpa().doubleValue() : null,
                "LOW", List.of(),
                note != null && !note.isBlank() ? "Acknowledged: " + note.strip() : "Acknowledged",
                r.totalCreditsEarned(), Instant.now().toString(), null, null, 0);
    }

    public List<AtRiskStudent> computeAllAtRisk(UUID advisorId) {
        return computeAllAtRisk(advisorId, null);
    }

    public List<AtRiskStudent> computeAllAtRisk(UUID advisorId, UUID semesterId) {
        List<AdviseeRow> advisees = queries.loadAdvisees(advisorId, null);
        List<AtRiskStudent> result = new ArrayList<>();
        String semester = semesterId == null ? null : semesterId.toString();
        for (AdviseeRow row : advisees) {
            AtRiskStudent evaluated = evaluator.evaluate(row, semester);
            if (evaluated != null) {
                result.add(evaluated);
            }
        }
        result.sort((a, b) -> {
            int rankDiff = AdvisorAtRiskEvaluator.riskRank(b.riskLevel())
                    - AdvisorAtRiskEvaluator.riskRank(a.riskLevel());
            if (rankDiff != 0) {
                return rankDiff;
            }
            double gpaA = a.currentGpa() != null ? a.currentGpa() : 10.0;
            double gpaB = b.currentGpa() != null ? b.currentGpa() : 10.0;
            return Double.compare(gpaA, gpaB);
        });
        return result;
    }

    public record AtRiskStudent(
            String id,
            String studentCode,
            String fullName,
            String majorCode,
            String majorName,
            Integer currentSemester,
            Double currentGpa,
            String riskLevel,
            List<String> reasons,
            String reasonDetails,
            int totalCreditsEarned,
            String detectedAt,
            /** Latest advising contact (session or email) with the student, null when never contacted. */
            String lastContactAt,
            String lastContactType,
            int contactCount
    ) {}

    public record AtRiskStudentResponse(
            List<AtRiskStudent> data,
            int total,
            int page,
            int size,
            int totalPages
    ) {}

    public record AtRiskCounts(int high, int medium, int low, int total) {}
}
