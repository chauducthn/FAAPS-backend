package vn.edu.fpt.faaps.advising.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.advising.repository.AssignmentReadRepository;

@Service
@ConditionalOnBean(JdbcTemplate.class)
public class AdminAssignmentSmartBatchService {
    private final AdminSmartBatchAssignHelper smartBatch;

    public AdminAssignmentSmartBatchService(JdbcTemplate jdbc, CohortAdvisorService cohorts) {
        AssignmentReadRepository reads = new AssignmentReadRepository(jdbc);
        AdminAssignmentSupport support = new AdminAssignmentSupport(jdbc, reads, cohorts);
        AdminAssignmentBatchHelper batch = new AdminAssignmentBatchHelper(reads, cohorts, support);
        this.smartBatch = new AdminSmartBatchAssignHelper(jdbc, cohorts, batch);
    }

    @Transactional
    public Map<String, Object> smartAutoAssignAll(
            List<UUID> cohortIds,
            boolean autoConfigurePools,
            boolean allowOverLimit,
            UUID actorId,
            String notes) {
        return smartBatch.runSmartBatch(cohortIds, autoConfigurePools, allowOverLimit, actorId, notes);
    }

    @Transactional
    public Map<String, Object> unassignAll(List<UUID> advisorIds, boolean clearPools, String notes) {
        return smartBatch.unassignAll(advisorIds, clearPools, notes);
    }
}
