package vn.edu.fpt.faaps.architecture.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import vn.edu.fpt.faaps.auth.model.AuthAccount;

class AdminApiConventionTest extends AdminApiConventionTestBase {

    @Test
    void securityConventionRoleChecksAndUnauthenticatedAccess() throws Exception {
        HttpClient anonClient = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();

        assertEquals(401, get(anonClient, "/api/admin/majors").statusCode());
        assertEquals(401, get(anonClient, "/api/admin/courses").statusCode());
        assertEquals(401, get(anonClient, "/api/admin/users").statusCode());
        assertEquals(401, get(anonClient, "/api/admin/academic-rules").statusCode());

        AuthAccount student = new AuthAccount(UUID.randomUUID(), "student@example.com", "Student User",
                "STUDENT", "ACTIVE", passwordEncoder.encode("secret"));
        accountStore.account.set(student);
        HttpResponse<String> studentLogin = postJson(anonClient, "/api/auth/admin/login",
                "{\"email\":\"student@example.com\",\"password\":\"secret\"}");
        assertEquals(401, studentLogin.statusCode());

        HttpClient adminClient = loginAsAdmin();
        assertEquals(403, get(adminClient, "/api/student/dashboard").statusCode());
        assertEquals(403, get(adminClient, "/api/advisor/dashboard").statusCode());
    }

    @Test
    void validationErrorReturns400WithCodeMessageAndFieldErrors() throws Exception {
        HttpClient adminClient = loginAsAdmin();

        HttpResponse<String> majorResp = postJson(adminClient, "/api/admin/majors",
                "{\"code\":\"\",\"name\":\"\",\"totalCredits\":-5}");
        assertEquals(400, majorResp.statusCode());
        assertTrue(majorResp.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(majorResp.body().contains("\"message\":\"Validation failed\""));
        assertTrue(majorResp.body().contains("\"fieldErrors\""));
        assertTrue(majorResp.body().contains("\"code\""));

        HttpResponse<String> courseResp = postJson(adminClient, "/api/admin/courses",
                "{\"code\":\"\",\"name\":\"\",\"credits\":-1}");
        assertEquals(400, courseResp.statusCode());
        assertTrue(courseResp.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(courseResp.body().contains("\"fieldErrors\""));

        HttpResponse<String> ruleResp = postJson(adminClient, "/api/admin/academic-rules",
                "{\"policyType\":\"\"}");
        assertEquals(400, ruleResp.statusCode());
        assertTrue(ruleResp.body().contains("\"code\":\"VALIDATION_ERROR\""));
        assertTrue(ruleResp.body().contains("\"fieldErrors\""));
    }

    @Test
    void notFoundReturns404CodeAndMessage() throws Exception {
        HttpClient adminClient = loginAsAdmin();
        UUID nonExistentId = UUID.randomUUID();

        HttpResponse<String> major404 = get(adminClient, "/api/admin/majors/" + nonExistentId);
        assertEquals(404, major404.statusCode());
        assertTrue(major404.body().contains("\"code\":\"NOT_FOUND\""));

        HttpResponse<String> course404 = get(adminClient, "/api/admin/courses/" + nonExistentId);
        assertEquals(404, course404.statusCode());
        assertTrue(course404.body().contains("\"code\":\"NOT_FOUND\""));

        HttpResponse<String> rule404 = get(adminClient, "/api/admin/academic-rules/" + nonExistentId);
        assertEquals(404, rule404.statusCode());
        assertTrue(rule404.body().contains("\"code\":\"NOT_FOUND\""));
    }

    @Test
    void listApiAdheresToPaginationSortingAndTotalElementsConvention() throws Exception {
        HttpClient adminClient = loginAsAdmin();

        HttpResponse<String> majorOptions = get(adminClient, "/api/admin/majors");
        assertEquals(200, majorOptions.statusCode());
        assertTrue(majorOptions.body().startsWith("["));

        HttpResponse<String> majorsList = get(adminClient,
                "/api/admin/majors/paged?search=&status=ACTIVE&page=0&size=10&sort=code,asc");
        assertEquals(200, majorsList.statusCode());
        assertTrue(majorsList.body().contains("\"totalElements\""));
        assertTrue(majorsList.body().contains("\"totalPages\""));
        assertTrue(majorsList.body().contains("\"page\""));
        assertTrue(majorsList.body().contains("\"size\""));
        assertTrue(majorsList.body().contains("\"items\""));

        HttpResponse<String> coursesList = get(adminClient,
                "/api/admin/courses?search=&status=ACTIVE&page=0&size=10&sort=code,asc");
        assertEquals(200, coursesList.statusCode());
        assertTrue(coursesList.body().contains("\"totalElements\""));
        assertTrue(coursesList.body().contains("\"items\""));

        HttpResponse<String> rulesList = get(adminClient, "/api/admin/academic-rules?page=0&size=10");
        assertEquals(200, rulesList.statusCode());
        assertTrue(rulesList.body().contains("\"totalElements\""));
        assertTrue(rulesList.body().contains("\"items\""));
    }
}
