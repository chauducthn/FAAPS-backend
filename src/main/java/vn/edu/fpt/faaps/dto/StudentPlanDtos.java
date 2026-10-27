package vn.edu.fpt.faaps.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class StudentPlanDtos {
    private StudentPlanDtos() {}
    public record Semester(String id,String code,String name,LocalDate startDate,LocalDate endDate) {}
    public record PlanHeader(String id,String semesterId,String semesterCode,int version,String status,
            String planType,int totalCredits,String previousPlanId,LocalDateTime createdAt) {}
    public record PlanCourse(String id,String code,String name,int credits,String priority,String notes) {}
    public record CurriculumPlan(PlanHeader header,List<PlanCourse> courses,List<String> issues) {}
    public record CourseHeader(String id,String courseId,String courseCode,String courseName,
            String learningGoal,int version,String previousPlanId,LocalDateTime createdAt) {}
    public record Task(String id,String title,String description,LocalDate dueDate,String resourceUrl,int sortOrder) {}
    public record TaskInput(String title,String description,LocalDate dueDate,String resourceUrl) {}
    public record CoursePlan(CourseHeader header,List<Task> tasks) {}
}

