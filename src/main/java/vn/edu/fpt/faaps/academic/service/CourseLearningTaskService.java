package vn.edu.fpt.faaps.academic.service;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.CoursePlan;
import vn.edu.fpt.faaps.academic.repository.CourseLearningTaskRepository;

@Service
public class CourseLearningTaskService {
    private final CourseLearningTaskRepository tasks;
    private final CourseLearningPlanService plans;

    public CourseLearningTaskService(CourseLearningTaskRepository tasks, CourseLearningPlanService plans) {
        this.tasks = tasks; this.plans = plans;
    }

    @Transactional
    public CoursePlan update(UUID studentId, UUID planId, UUID taskId, boolean completed) {
        if (tasks.updateProgress(studentId, planId, taskId, completed) != 1)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Learning task not found");
        return plans.get(studentId, planId);
    }
}
