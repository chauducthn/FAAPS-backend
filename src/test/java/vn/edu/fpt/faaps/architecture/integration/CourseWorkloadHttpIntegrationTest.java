package vn.edu.fpt.faaps.architecture.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.auth.model.AuthAccount;

/** End-to-end HTTP + Spring Security + CSRF verification for Layer 2 workload override endpoints. */
class CourseWorkloadHttpIntegrationTest extends AdminApiConventionTestBase {

    @Test
    void workloadOverrideEnforcesRolesAndPersistsViaHttpWithCsrf() throws Exception {
        HttpClient admin = loginAsAdmin();

        // Create a course via Admin API (controller accepts SSL101c; service stores uppercase SSL101C)
        String createBody = """
                {"code":"SSL101c","name":"Academic Skills","credits":3,"categoryId":"%s","status":"ACTIVE"}
                """.formatted(categoryId);
        HttpResponse<String> created = postJson(admin, "/api/admin/courses", createBody);
        assertEquals(201, created.statusCode(), created.body());
        String courseId = extractJsonString(created.body(), "id");
        assertNotNull(courseId);

        // Admin overrides workload to HEAVY via PUT + CSRF
        HttpResponse<String> adminPut = putJson(admin, "/api/admin/courses/" + courseId + "/workload",
                "{\"workloadLevel\":\"HEAVY\"}");
        assertEquals(200, adminPut.statusCode(), adminPut.body());
        assertTrue(adminPut.body().contains("\"level\":\"HEAVY\""), adminPut.body());
        assertTrue(adminPut.body().contains("\"source\":\"MANUAL\""), adminPut.body());

        // Filter list by workloadLevel=HEAVY returns the overridden course
        HttpResponse<String> filtered = get(admin, "/api/admin/courses?workloadLevel=HEAVY");
        assertEquals(200, filtered.statusCode(), filtered.body());
        assertTrue(filtered.body().contains("SSL101c"), filtered.body());

        // Admin resets to Auto using empty/null payload
        HttpResponse<String> reset = putJson(admin, "/api/admin/courses/" + courseId + "/workload",
                "{\"workloadLevel\":null}");
        assertEquals(200, reset.statusCode(), reset.body());
        assertTrue(reset.body().contains("\"source\":\"RULE_BASED\""), reset.body());

        // Advisor can override via /api/advisor/academic/courses/{id}/workload, but gets 403 on /api/admin/**
        HttpClient advisor = loginAsRole("advisor@example.com", "ADVISOR");
        HttpResponse<String> advisorPut = putJson(advisor, "/api/advisor/academic/courses/" + courseId + "/workload",
                "{\"workloadLevel\":\"MEDIUM\"}");
        assertEquals(200, advisorPut.statusCode(), advisorPut.body());
        assertTrue(advisorPut.body().contains("\"level\":\"MEDIUM\""), advisorPut.body());
        assertTrue(advisorPut.body().contains("\"source\":\"MANUAL\""), advisorPut.body());

        HttpResponse<String> advisorOnAdmin = putJson(advisor, "/api/admin/courses/" + courseId + "/workload",
                "{\"workloadLevel\":\"LIGHT\"}");
        assertEquals(403, advisorOnAdmin.statusCode());

        // Student gets 403 on both admin and advisor workload override endpoints
        HttpClient student = loginAsRole("student@example.com", "STUDENT");
        assertEquals(403, putJson(student, "/api/admin/courses/" + courseId + "/workload",
                "{\"workloadLevel\":\"LIGHT\"}").statusCode());
        assertEquals(403, putJson(student, "/api/advisor/academic/courses/" + courseId + "/workload",
                "{\"workloadLevel\":\"LIGHT\"}").statusCode());
    }

    private HttpClient loginAsRole(String email, String role) throws Exception {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
        AuthAccount acc = new AuthAccount(UUID.randomUUID(), email, role + " User",
                role, "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(acc);
        HttpResponse<String> login = postJson(client, "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"secret\"}");
        assertEquals(200, login.statusCode(), login.body());
        return client;
    }

    private HttpResponse<String> putJson(HttpClient client, String path, String json) throws Exception {
        String csrf = getCsrfToken(client);
        return send(client, HttpRequest.newBuilder(baseUrl.resolve(path))
                .header("Content-Type", "application/json")
                .header("X-XSRF-TOKEN", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }
}
