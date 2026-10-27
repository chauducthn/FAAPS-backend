package vn.edu.fpt.faaps.academic.service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.Detail;
import vn.edu.fpt.faaps.academic.dto.AdvisorPlanDtos.Page;
import vn.edu.fpt.faaps.academic.repository.AdvisorStudyPlanRepository;
import vn.edu.fpt.faaps.academic.repository.AdvisorStudyPlanRepository.Student;

@Service
public class AdvisorStudyPlanService {
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED", "NEEDS_REVISION");
    private final AdvisorStudyPlanRepository repository;
    private final StudentPlanService plans;

    public AdvisorStudyPlanService(AdvisorStudyPlanRepository repository, StudentPlanService plans) {
        this.repository = repository; this.plans = plans;
    }

    public Page list(UUID advisorId, String status, int page, int size) {
        return repository.list(advisorId, status, page, size);
    }

    public Detail detail(UUID advisorId, UUID planId) {
        Student student = requireStudent(advisorId, planId, false);
        return new Detail(student.id().toString(), student.code(), student.name(),
                plans.curriculumPlan(student.id(), planId));
    }

    @Transactional
    public Detail decide(UUID advisorId, UUID planId, String rawDecision, String rawComment) {
        Student student = requireStudent(advisorId, planId, true);
        String decision = normalizeDecision(rawDecision);
        String comment = rawComment == null ? null : rawComment.strip();
        if (!"APPROVED".equals(decision) && (comment == null || comment.isBlank()))
            throw new IllegalArgumentException("A comment is required when the plan is not approved");
        if (repository.decide(planId, advisorId, decision, comment) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a current submitted plan can be reviewed");
        repository.notifyStudent(student.id(), planId, decision);
        return detail(advisorId, planId);
    }

    private Student requireStudent(UUID advisorId, UUID planId, boolean lock) {
        Student student = repository.student(advisorId, planId, lock);
        if (student == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Study plan not found");
        return student;
    }

    private String normalizeDecision(String value) {
        String decision = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("Invalid study plan decision");
        return decision;
    }
}
