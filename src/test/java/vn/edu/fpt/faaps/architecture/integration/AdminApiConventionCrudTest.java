package vn.edu.fpt.faaps.architecture.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;

class AdminApiConventionCrudTest extends AdminApiConventionTestBase {

    @Test
    void dataConflictReturns409CodeAndMessage() throws Exception {
        HttpClient adminClient = loginAsAdmin();

        String createMajorJson = """
                {"code":"IA","name":"Information Assurance","totalCredits":140,"durationYears":4.0,"status":"ACTIVE"}
                """;
        assertEquals(201, postJson(adminClient, "/api/admin/majors", createMajorJson).statusCode());
        HttpResponse<String> duplicateMajor = postJson(adminClient, "/api/admin/majors", createMajorJson);
        assertEquals(409, duplicateMajor.statusCode());
        assertTrue(duplicateMajor.body().contains("\"code\":\"CONFLICT\""));

        String createCourseJson = """
                {"code":"PRJ301","name":"Java Web Application","credits":3,"categoryId":"%s"}
                """.formatted(categoryId);
        assertEquals(201, postJson(adminClient, "/api/admin/courses", createCourseJson).statusCode());
        HttpResponse<String> duplicateCourse = postJson(adminClient, "/api/admin/courses", createCourseJson);
        assertEquals(409, duplicateCourse.statusCode());
        assertTrue(duplicateCourse.body().contains("\"code\":\"CONFLICT\""));

        String majorWithCurriculum = """
                {"code":"SE_COHORT","name":"Software Eng Cohort","totalCredits":140,"durationYears":4.0,"status":"ACTIVE","cohort":"K19"}
                """;
        HttpResponse<String> curMajor = postJson(adminClient, "/api/admin/majors", majorWithCurriculum);
        assertEquals(201, curMajor.statusCode());
        String curMajorId = extractJsonString(curMajor.body(), "id");

        HttpResponse<String> deleteCurConflict = delete(adminClient, "/api/admin/majors/" + curMajorId);
        assertEquals(409, deleteCurConflict.statusCode());
        assertTrue(deleteCurConflict.body().contains("\"code\":\"CONFLICT\""));
    }

    @Test
    void fullCrudFlowAcrossAllThreeModules() throws Exception {
        HttpClient adminClient = loginAsAdmin();

        String majorJson = """
                {"code":"AI","name":"Artificial Intelligence","totalCredits":145,"durationYears":4.0,"status":"ACTIVE"}
                """;
        HttpResponse<String> createdMajor = postJson(adminClient, "/api/admin/majors", majorJson);
        assertEquals(201, createdMajor.statusCode());
        String majorId = extractJsonString(createdMajor.body(), "id");
        assertEquals(200, get(adminClient, "/api/admin/majors/" + majorId).statusCode());

        String csrf = getCsrfToken(adminClient);
        HttpResponse<String> updatedMajor = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/majors/" + majorId))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"name\":\"AI & Data Science\"}"))
                .build());
        assertEquals(200, updatedMajor.statusCode());
        assertTrue(updatedMajor.body().contains("AI & Data Science"));
        assertEquals(200, delete(adminClient, "/api/admin/majors/" + majorId).statusCode());

        String courseJson = """
                {"code":"SWE201","name":"Software Engineering Fundamentals","credits":3,"categoryId":"%s","status":"ACTIVE"}
                """.formatted(categoryId);
        HttpResponse<String> createdCourse = postJson(adminClient, "/api/admin/courses", courseJson);
        assertEquals(201, createdCourse.statusCode());
        String courseId = extractJsonString(createdCourse.body(), "id");
        assertEquals(200, get(adminClient, "/api/admin/course-categories").statusCode());
        assertEquals(200, get(adminClient, "/api/admin/courses/" + courseId).statusCode());
        assertEquals(200, delete(adminClient, "/api/admin/courses/" + courseId).statusCode());

        String ruleJson = """
                {"curriculumVersionId":"%s","policyType":"MAX_CREDITS_PER_SEMESTER","conditionExpression":"{\\"max\\":20}","displayMessage":"Max 20 credits per term"}
                """.formatted(curriculumVersionId);
        HttpResponse<String> createdRule = postJson(adminClient, "/api/admin/academic-rules", ruleJson);
        assertEquals(201, createdRule.statusCode());
        String ruleId = extractJsonString(createdRule.body(), "id");
        assertEquals(200, get(adminClient, "/api/admin/academic-rules/types").statusCode());

        csrf = getCsrfToken(adminClient);
        HttpResponse<String> toggleResp = send(adminClient, HttpRequest.newBuilder(baseUrl.resolve("/api/admin/academic-rules/" + ruleId + "/status"))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"isActive\":false}"))
                .build());
        assertEquals(200, toggleResp.statusCode());
        assertTrue(toggleResp.body().contains("\"isActive\":false"));
        assertEquals(200, delete(adminClient, "/api/admin/academic-rules/" + ruleId).statusCode());
    }
}
