package vn.edu.fpt.faaps.academic.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CourseLearningTaskRepository {
    private final JdbcTemplate jdbc;
    public CourseLearningTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public int updateProgress(UUID studentId, UUID planId, UUID taskId, boolean completed) {
        return jdbc.update("""
                UPDATE course_learning_tasks SET completed=?,
                    completed_at=CASE WHEN ? THEN COALESCE(completed_at,CURRENT_TIMESTAMP) ELSE NULL END
                WHERE id=? AND plan_id=? AND EXISTS (
                    SELECT 1 FROM course_learning_plans p
                    WHERE p.id=course_learning_tasks.plan_id AND p.student_id=?
                )
                """, completed, completed, taskId.toString(), planId.toString(), studentId.toString());
    }
}
