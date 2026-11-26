package vn.edu.fpt.faaps.advising.controller;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.edu.fpt.faaps.advising.service.AdminAssignmentSmartBatchService;
import vn.edu.fpt.faaps.auth.model.AuthSession;

@RestController
@ConditionalOnBean(JdbcTemplate.class)
@RequestMapping("/api/admin/assignments")
public class AdminAssignmentBatchController {
    private final AdminAssignmentSmartBatchService smartBatch;

    public AdminAssignmentBatchController(AdminAssignmentSmartBatchService smartBatch) {
        this.smartBatch = smartBatch;
    }

    @PostMapping("/auto-assign/smart-all")
    public Map<String, Object> smartAutoAssignAll(
            @AuthenticationPrincipal AuthSession session,
            @RequestBody(required = false) SmartBatchRequest request) {
        SmartBatchRequest req = request == null ? new SmartBatchRequest(null, false, false, null) : request;
        return smartBatch.smartAutoAssignAll(
                req.cohortIds(),
                Boolean.TRUE.equals(req.autoConfigurePools()),
                Boolean.TRUE.equals(req.allowOverLimit()),
                session.id(),
                req.notes());
    }

    @PostMapping("/unassign-all")
    public Map<String, Object> unassignAll(@RequestBody(required = false) UnassignAllRequest request) {
        UnassignAllRequest req = request == null ? new UnassignAllRequest(null, false, null) : request;
        return smartBatch.unassignAll(req.advisorIds(), Boolean.TRUE.equals(req.clearPools()), req.notes());
    }

    public record SmartBatchRequest(
            List<UUID> cohortIds,
            Boolean autoConfigurePools,
            Boolean allowOverLimit,
            String notes) {
    }

    public record UnassignAllRequest(List<UUID> advisorIds, Boolean clearPools, String notes) {
    }
}
