package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import vn.edu.fpt.faaps.academic.controller.AdminCourseController;

class AdminCourseServiceTest {
    private JdbcTemplate jdbc;
    private AdminCourseService courses;
    private UUID catGeneral;
    private UUID catSpecialized;

    @BeforeEach
    void setUp() {
        AdminCourseTestSupport.Fixture fixture = AdminCourseTestSupport.createFixture();
        jdbc = fixture.jdbc();
        courses = fixture.courses();
        catGeneral = fixture.catGeneral();
        catSpecialized = fixture.catSpecialized();
    }

    @Test
    void rejectsInvalidSortParameter() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> courses.list(null, null, null, 0, 10, "foo,asc"));
        assertEquals("sort is invalid", ex.getMessage());
    }

    @Test
    void allowsValidSortParameters() {
        courses.create("PRF192", "Programming Fundamentals", 3, catGeneral, null, null, null, "ACTIVE", List.of());
        assertNotNull(courses.list(null, null, null, 0, 10, "code,asc"));
        assertNotNull(courses.list(null, null, null, 0, 10, "code,desc"));
        assertNotNull(courses.list(null, null, null, 0, 10, "name,asc"));
        assertNotNull(courses.list(null, null, null, 0, 10, "credits,desc"));
    }

    @Test
    void createsCourseWithPrerequisitesAndLists() {
        AdminCourseService.CourseDetail prf = courses.create("PRF192", "Programming Fundamentals", 3, catGeneral,
                "Intro to programming", null, null, "ACTIVE", List.of());
        assertNotNull(prf.id());

        AdminCourseService.PrerequisiteInput prereqInput = new AdminCourseService.PrerequisiteInput(
                prf.id(), 1, "PREREQUISITE", new BigDecimal("5.00"));
        AdminCourseService.CourseDetail pro = courses.create("PRO192", "Object-Oriented Programming", 3, catSpecialized,
                "Java OOP", null, null, "ACTIVE", List.of(prereqInput));

        assertEquals(1, pro.prerequisites().size());
        assertEquals("PRF192", pro.prerequisites().get(0).prerequisiteCode());

        AdminCourseService.Page page = courses.list("PRO192", null, null, 0, 10, null);
        assertEquals(1, page.totalElements());
        assertEquals("PRO192", page.items().get(0).code());
        assertEquals(1, page.items().get(0).prerequisiteCount());
    }

    @Test
    void acceptsReplacementAndRejectsUnsupportedRelationType() {
        AdminCourseService.CourseDetail original = courses.create("PRF192", "Programming Fundamentals", 3,
                catGeneral, null, null, null, "ACTIVE", List.of());
        AdminCourseService.CourseDetail replacement = courses.create("PRF193", "Programming Fundamentals II", 3,
                catGeneral, null, null, null, "ACTIVE", List.of());
        AdminCourseService.CourseDetail target = courses.create("PRO192", "Object-Oriented Programming", 3,
                catSpecialized, null, null, null, "ACTIVE", List.of(
                        new AdminCourseService.PrerequisiteInput(original.id(), 1, "PREREQUISITE", null),
                        new AdminCourseService.PrerequisiteInput(replacement.id(), 1, "REPLACEMENT", null)));

        assertEquals(2, target.prerequisites().size());
        assertEquals("REPLACEMENT", target.prerequisites().get(1).type());
        assertNull(target.prerequisites().get(1).minimumScore());
        assertThrows(IllegalArgumentException.class, () -> courses.update(target.id(), null, null, null,
                null, null, null, null, null, List.of(
                        new AdminCourseService.PrerequisiteInput(original.id(), 1, "UNSUPPORTED", null))));
        assertEquals(2, courses.detail(target.id()).prerequisites().size());
    }

    @Test
    void preventsDeletingCourseReferencedAsPrerequisite() {
        AdminCourseService.CourseDetail prf = courses.create("PRF192", "Programming Fundamentals", 3, catGeneral,
                null, null, null, "ACTIVE", List.of());
        courses.create("PRO192", "Object-Oriented Programming", 3, catSpecialized,
                null, null, null, "ACTIVE", List.of(new AdminCourseService.PrerequisiteInput(prf.id(), 1, "PREREQUISITE", null)));

        assertThrows(AdminCourseService.Conflict.class, () -> courses.delete(prf.id()));
    }

    @Test
    void deletingCourseInCurriculumReturnsConflictAndPreservesCourse() {
        AdminCourseService.CourseDetail course = courses.create("MAT101", "Mathematics", 3, catGeneral,
                null, null, null, "ACTIVE", List.of());
        jdbc.update("INSERT INTO curriculum_courses (id, curriculum_version_id, course_id) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), course.id().toString());

        AdminCourseService.Conflict conflict = assertThrows(AdminCourseService.Conflict.class,
                () -> courses.delete(course.id()));
        assertEquals(HttpStatus.CONFLICT, new AdminCourseController(courses).conflict(conflict).getStatusCode());
        assertEquals(course.id(), courses.detail(course.id()).id());
    }

    @Test
    void materialLinkIsStoredSeparatelyFromObjectivesValidatedAndClearedByNullOrEmptyString() {
        AdminCourseController controller = new AdminCourseController(courses);
        AdminCourseController.CreateCourseRequest createReq = new AdminCourseController.CreateCourseRequest(
                "PRF192", "Programming Fundamentals", 3, catGeneral,
                "Desc", "Real objectives text...", "LO text",
                "https://drive.google.com/drive/folders/abc123", "ACTIVE", List.of());
        AdminCourseService.CourseDetail created = controller.create(createReq).getBody();
        assertNotNull(created);
        assertEquals("Real objectives text...", created.objectives());
        assertEquals("https://drive.google.com/drive/folders/abc123", created.materialLink());

        AdminCourseService.CourseDetail updatedLink = controller.update(created.id(),
                new AdminCourseController.UpdateCourseRequest("PRF192", "Programming Fundamentals", 3, catGeneral,
                        null, null, null, "https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRF192",
                        "ACTIVE", null));
        assertEquals("Real objectives text...", updatedLink.objectives());
        assertEquals("https://flm.fpt.edu.vn/gui/role/student/SyllabusDetails?sylId=PRF192", updatedLink.materialLink());

        AdminCourseService.CourseDetail clearedByNull = controller.update(created.id(),
                new AdminCourseController.UpdateCourseRequest("PRF192", "Programming Fundamentals", 3, catGeneral,
                        null, null, null, null, "ACTIVE", null));
        assertNull(clearedByNull.materialLink());
        assertEquals("Real objectives text...", clearedByNull.objectives());

        controller.update(created.id(), new AdminCourseController.UpdateCourseRequest("PRF192", "Programming Fundamentals",
                3, catGeneral, null, null, null, "https://drive.google.com/drive/folders/abc123", "ACTIVE", null));
        AdminCourseService.CourseDetail clearedByEmpty = controller.update(created.id(),
                new AdminCourseController.UpdateCourseRequest("PRF192", "Programming Fundamentals", 3, catGeneral,
                        null, null, null, "", "ACTIVE", null));
        assertNull(clearedByEmpty.materialLink());
        assertEquals("Real objectives text...", clearedByEmpty.objectives());

        assertThrows(IllegalArgumentException.class, () -> controller.create(new AdminCourseController.CreateCourseRequest(
                "PRO192", "OOP", 3, catGeneral, null, null, null, "not a link", "ACTIVE", List.of())));
        String longLink = "https://example.com/" + "a".repeat(500);
        assertThrows(IllegalArgumentException.class, () -> controller.create(new AdminCourseController.CreateCourseRequest(
                "PRO192", "OOP", 3, catGeneral, null, null, null, longLink, "ACTIVE", List.of())));

        var bulkResp = controller.bulkStatus(new AdminCourseController.BulkStatusRequest(List.of(created.id()), "INACTIVE"));
        assertEquals(HttpStatus.OK, bulkResp.getStatusCode());
        assertEquals("INACTIVE", courses.detail(created.id()).status());
    }
}
