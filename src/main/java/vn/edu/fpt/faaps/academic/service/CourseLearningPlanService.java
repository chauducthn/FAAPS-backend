package vn.edu.fpt.faaps.academic.service;

import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import vn.edu.fpt.faaps.academic.dto.StudentPlanDtos.*;

@Service
public class CourseLearningPlanService {
    private final JdbcTemplate jdbc;
    public CourseLearningPlanService(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public List<CoursePlan> list(UUID studentId) {
        return jdbc.query("""
                SELECT p.id FROM course_learning_plans p WHERE p.student_id=? AND p.version=(
                  SELECT MAX(next.version) FROM course_learning_plans next
                  WHERE next.student_id=p.student_id AND next.course_id=p.course_id)
                ORDER BY p.created_at DESC
                """,(rs,row)->UUID.fromString(rs.getString(1)),studentId.toString()).stream().map(id->get(studentId,id)).toList();
    }
    public CoursePlan get(UUID studentId,UUID planId) {
        CourseHeader header=jdbc.query("""
                SELECT p.id,p.course_id,c.code,c.name,p.learning_goal,p.version,p.previous_plan_id,p.created_at
                FROM course_learning_plans p JOIN courses c ON c.id=p.course_id WHERE p.id=? AND p.student_id=?
                """,(rs,row)->new CourseHeader(rs.getString("id"),rs.getString("course_id"),rs.getString("code"),
                rs.getString("name"),rs.getString("learning_goal"),rs.getInt("version"),rs.getString("previous_plan_id"),
                rs.getTimestamp("created_at").toLocalDateTime()),planId.toString(),studentId.toString()).stream()
                .findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Course plan not found"));
        List<Task> tasks=jdbc.query("""
                SELECT id,title,description,due_date,resource_url,sort_order FROM course_learning_tasks
                WHERE plan_id=? ORDER BY sort_order
                """,(rs,row)->new Task(rs.getString("id"),rs.getString("title"),rs.getString("description"),
                rs.getDate("due_date")==null?null:rs.getDate("due_date").toLocalDate(),rs.getString("resource_url"),
                rs.getInt("sort_order")),planId.toString());
        return new CoursePlan(header,tasks);
    }
    public List<CoursePlan> versions(UUID studentId,UUID planId) {
        CoursePlan selected=get(studentId,planId);
        return jdbc.query("SELECT id FROM course_learning_plans WHERE student_id=? AND course_id=? ORDER BY version DESC",
                (rs,row)->UUID.fromString(rs.getString(1)),studentId.toString(),selected.header().courseId()).stream()
                .map(id->get(studentId,id)).toList();
    }
    @Transactional
    public CoursePlan save(UUID studentId,UUID courseId,String goal,List<TaskInput> tasks) {
        validate(courseId,goal,tasks); lockStudent(studentId);
        Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM courses WHERE id=? AND status='ACTIVE'",Integer.class,courseId.toString());
        if(active==null||active==0) throw new IllegalArgumentException("Course not found");
        Integer allowed=jdbc.queryForObject("SELECT COUNT(*) FROM curriculum_courses WHERE curriculum_version_id=? AND course_id=?",
                Integer.class,curriculumId(studentId),courseId.toString());
        if(allowed==null||allowed==0) throw new IllegalArgumentException("Course is outside your curriculum");
        List<String> previous=jdbc.query("SELECT id FROM course_learning_plans WHERE student_id=? AND course_id=? ORDER BY version DESC LIMIT 1 FOR UPDATE",
                (rs,row)->rs.getString(1),studentId.toString(),courseId.toString());
        int next=jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM course_learning_plans WHERE student_id=? AND course_id=?",
                Integer.class,studentId.toString(),courseId.toString());
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO course_learning_plans(id,student_id,course_id,learning_goal,version,previous_plan_id) VALUES (?,?,?,?,?,?)",
                id,studentId.toString(),courseId.toString(),goal,next,previous.isEmpty()?null:previous.get(0));
        for(int i=0;i<tasks.size();i++){TaskInput task=tasks.get(i);jdbc.update("""
                INSERT INTO course_learning_tasks(id,plan_id,title,description,due_date,resource_url,sort_order)
                VALUES (?,?,?,?,?,?,?)
                """,UUID.randomUUID().toString(),id,task.title().strip(),task.description(),task.dueDate(),task.resourceUrl(),i);}
        return get(studentId,UUID.fromString(id));
    }
    private void validate(UUID courseId,String goal,List<TaskInput> tasks) {
        if(courseId==null||tasks==null||tasks.isEmpty()||tasks.size()>100||tasks.contains(null)) throw new IllegalArgumentException("Choose a course and 1 to 100 learning tasks");
        if(goal!=null&&goal.length()>10_000) throw new IllegalArgumentException("Learning goal is too long");
        for(TaskInput t:tasks){if(t.title()==null||t.title().isBlank()||t.title().length()>255) throw new IllegalArgumentException("Each task needs a title up to 255 characters");
            if(t.description()!=null&&t.description().length()>10_000) throw new IllegalArgumentException("Task description is too long");
            if(t.resourceUrl()!=null&&t.resourceUrl().length()>1000) throw new IllegalArgumentException("Resource URL is too long");}
    }
    private String curriculumId(UUID id){return jdbc.query("SELECT curriculum_version_id FROM students WHERE user_id=?",(rs,row)->rs.getString(1),id.toString()).stream().findFirst().orElseThrow(()->new IllegalArgumentException("Academic profile not assigned"));}
    private void lockStudent(UUID id){if(jdbc.query("SELECT user_id FROM students WHERE user_id=? FOR UPDATE",(rs,row)->rs.getString(1),id.toString()).isEmpty()) throw new IllegalArgumentException("Your academic profile has not been assigned");}
}


