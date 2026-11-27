package vn.edu.fpt.faaps.academic.controller;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.dto.CourseWorkload;
import vn.edu.fpt.faaps.academic.service.AdminCourseService;
import vn.edu.fpt.faaps.academic.service.CourseWorkloadService;

/** Layer 2 manual workload override endpoints for Admin and Advisor roles. */
@RestController
public class CourseWorkloadController {
    private final CourseWorkloadService workloads;

    public CourseWorkloadController(CourseWorkloadService workloads) {
        this.workloads = workloads;
    }

    @RequestMapping(
            value = {
                    "/api/admin/courses/{id}/workload",
                    "/api/advisor/academic/courses/{id}/workload",
                    "/advisor/academic/courses/{id}/workload"
            },
            method = {RequestMethod.PUT, RequestMethod.PATCH})
    public CourseWorkload updateWorkload(
            @PathVariable UUID id, @RequestBody(required = false) WorkloadOverrideRequest request) {
        String raw = request == null ? null : request.workloadLevel();
        return workloads.updateManualLevel(id, raw);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> invalid(IllegalArgumentException ex) {
        return AcademicErrorResponses.invalidWithError(ex);
    }

    @ExceptionHandler(AdminCourseService.NotFound.class)
    public ResponseEntity<Map<String, Object>> notFound(AdminCourseService.NotFound ex) {
        return AcademicErrorResponses.statusWithError(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    public record WorkloadOverrideRequest(String workloadLevel) {}
}
