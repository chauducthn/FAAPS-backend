package vn.edu.fpt.faaps.academic.controller;

import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.academic.service.CourseEquivalenceService;
import vn.edu.fpt.faaps.academic.service.StudentBlockPlanService;
import vn.edu.fpt.faaps.academic.service.StudentBlockPlanService.BatchPlacementInput;
import vn.edu.fpt.faaps.academic.service.StudentBlockPlanService.BlockPlacement;
import vn.edu.fpt.faaps.academic.service.StudentBlockPlanService.PlacementInput;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@RequestMapping({"/api/student/curriculum/block-plan", "/student/curriculum/block-plan"})
public class StudentBlockPlanController {
    private final StudentBlockPlanService service;
    private final CourseEquivalenceService equivalences;

    public StudentBlockPlanController(StudentBlockPlanService service, CourseEquivalenceService equivalences) {
        this.service = service;
        this.equivalences = equivalences;
    }

    @GetMapping
    public List<BlockPlacement> list(@AuthenticationPrincipal AuthSession student) {
        return service.list(student.id());
    }

    @PutMapping
    public List<BlockPlacement> upsert(@AuthenticationPrincipal AuthSession student,
            @RequestBody PlacementInput input) {
        return service.upsert(student.id(), input);
    }

    @PutMapping("/batch")
    public List<BlockPlacement> batchUpdate(@AuthenticationPrincipal AuthSession student,
            @RequestBody BatchPlacementInput input) {
        return service.batchUpdate(student.id(), input);
    }

    @DeleteMapping("/{courseCode}")
    public List<BlockPlacement> remove(@AuthenticationPrincipal AuthSession student,
            @PathVariable String courseCode) {
        return service.remove(student.id(), courseCode);
    }

    @DeleteMapping
    public List<BlockPlacement> resetAll(@AuthenticationPrincipal AuthSession student) {
        return service.resetAll(student.id());
    }

    @GetMapping("/prerequisites")
    public Map<String, List<String>> prerequisites() {
        return service.prerequisiteMap();
    }

    @GetMapping("/equivalents")
    public Map<String, List<String>> equivalents() {
        return equivalences.equivalenceMap();
    }
}
