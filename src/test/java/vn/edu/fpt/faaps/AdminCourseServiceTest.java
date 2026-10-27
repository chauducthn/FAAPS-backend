package vn.edu.fpt.faaps;

import vn.edu.fpt.faaps.controller.AdminCourseController;
import vn.edu.fpt.faaps.service.AdminCourseService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AdminCourseServiceTest {
    private JdbcTemplate jdbc;
    private AdminCourseService courses;
    private UUID catGeneral;
    private UUID catSpecialized;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-courses-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE course_categories (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL,
                    description TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE courses (
                    id VARCHAR(36) PRIMARY KEY,
                    code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL,
                    credits INT NOT NULL,
                    category_id VARCHAR(36) NOT NULL,
                    description TEXT,
                    objectives TEXT,
                    learning_outcomes TEXT,
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_prerequisites (
                    id VARCHAR(36) PRIMARY KEY,
                    course_id VARCHAR(36) NOT NULL,
                    prerequisite_course_id VARCHAR(36) NOT NULL,
                    group_no INT DEFAULT 1,
                    type VARCHAR(20) DEFAULT 'PREREQUISITE',
                    minimum_score DECIMAL(4,2),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY,
                    curriculum_version_id VARCHAR(36),
                    course_id VARCHAR(36)
                )
                """);

        catGeneral = UUID.randomUUID();
        catSpecialized = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'GEN', 'General Education')", catGeneral.toString());
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'SPEC', 'Specialized Knowledge')", catSpecialized.toString());

        courses = new AdminCourseService(jdbc);
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
    void parsesAndPreviewsXlsxFile() throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Courses");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Code");
        header.createCell(1).setCellValue("Name");
        header.createCell(2).setCellValue("Credits");
        header.createCell(3).setCellValue("Category");
        header.createCell(4).setCellValue("Description");

        Row row1 = sheet.createRow(1);
        row1.createCell(0).setCellValue("CSD201");
        row1.createCell(1).setCellValue("Data Structures and Algorithms");
        row1.createCell(2).setCellValue(3);
        row1.createCell(3).setCellValue("SPEC");
        row1.createCell(4).setCellValue("Trees, graphs, dynamic programming");

        Row row2 = sheet.createRow(2);
        row2.createCell(0).setCellValue("INVALID CODE!!");
        row2.createCell(1).setCellValue("Invalid Course");
        row2.createCell(2).setCellValue("NotANumber");
        row2.createCell(3).setCellValue("NON_EXISTENT_CAT");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        AdminCourseService.ImportPreviewResult preview = courses.previewXlsx(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(2, preview.totalRows());
        assertEquals(1, preview.validRows());
        assertEquals(1, preview.errorRows());

        AdminCourseService.ImportRowItem validRow = preview.items().get(0);
        assertEquals("CSD201", validRow.code());
        assertEquals("VALID", validRow.status());

        AdminCourseService.ImportRowItem errorRow = preview.items().get(1);
        assertEquals("ERROR", errorRow.status());

        int confirmed = courses.confirmImport(List.of(new AdminCourseService.ImportConfirmItem(
                validRow.code(), validRow.name(), validRow.credits(), UUID.fromString(validRow.categoryId()), validRow.description())));
        assertEquals(1, confirmed);
        assertEquals(1, courses.list("CSD201", null, null, 0, 10, null).totalElements());
    }

    @Test
    void importsFlmSyllabusDatasetHorizontal() throws Exception {
        courses.create("DBI202", "Introduction to Databases", 3, catGeneral, "Database fundamentals", null, null, "ACTIVE", List.of());
        courses.create("PRO192", "Object-Oriented Programming", 3, catGeneral, "Java fundamentals", null, null, "ACTIVE", List.of());

        String prjDescription = """
                By the end of this course Students will be able to:
                a) Knowledge: (what will students know?)
                • Understand the core technologies of Java web programming:
                - Servlet and JSP
                - Scope of sharing state (session, application, request,page)
                • Know how to develop and deploy your own websites using Java
                • Understand and be able to apply MVC architecture for the web
                - Learning how to apply JPA in websites using Java
                • Learning how to apply AI in your own website using Java
                b) Skills: (what will students be able to do?)
                • Basic Web application development applying MVC Design Pattern using Servlet/Filter as Controller
                • Creating a simple Web Application demo using JPA""";

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Syllabus");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Syllabus ID");
        header.createCell(1).setCellValue("Syllabus Name");
        header.createCell(2).setCellValue("Course Name English");
        header.createCell(3).setCellValue("Subject Code");
        header.createCell(4).setCellValue("Learning-Teaching Method");
        header.createCell(5).setCellValue("NoCredit");
        header.createCell(6).setCellValue("Degree Level");
        header.createCell(7).setCellValue("Time Allocation");
        header.createCell(8).setCellValue("Pre-Requisite");
        header.createCell(9).setCellValue("Description");

        Row row = sheet.createRow(1);
        row.createCell(0).setCellValue("13165");
        row.createCell(1).setCellValue("Java Web Application Development_Phát triển ứng dụng Java web");
        row.createCell(2).setCellValue("Java Web Application Development");
        row.createCell(3).setCellValue("PRJ301");
        row.createCell(4).setCellValue("Project based learning,Active learning,Social Constructivism,In-class lecture");
        row.createCell(5).setCellValue(3);
        row.createCell(6).setCellValue("Bachelor");
        row.createCell(7).setCellValue("Study hour (150h) = 45h contact hours + 1h final exam + 85' practical exam + 102.6h self-study");
        row.createCell(8).setCellValue("DBI202, PRO192");
        row.createCell(9).setCellValue(prjDescription);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        AdminCourseService.ImportPreviewResult preview = courses.previewXlsx(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(1, preview.totalRows());
        assertEquals(1, preview.validRows());
        assertEquals(0, preview.errorRows());

        AdminCourseService.ImportRowItem item = preview.items().get(0);
        assertEquals("PRJ301", item.code());
        assertEquals("Java Web Application Development", item.name());
        assertEquals(3, item.credits());
        assertEquals("DBI202, PRO192", item.prerequisites());
        assertEquals("VALID", item.status());

        int confirmed = courses.confirmImport(List.of(new AdminCourseService.ImportConfirmItem(
                item.code(), item.name(), item.credits(), UUID.fromString(item.categoryId()), item.description(), item.prerequisites())));
        assertEquals(1, confirmed);

        AdminCourseService.Page listPage = courses.list("PRJ301", null, null, 0, 10, null);
        assertEquals(1, listPage.totalElements());
        UUID prjId = listPage.items().get(0).id();

        AdminCourseService.CourseDetail detail = courses.detail(prjId);
        assertEquals("PRJ301", detail.code());
        assertEquals("Java Web Application Development", detail.name());
        assertEquals(3, detail.credits());
        assertEquals(2, detail.prerequisites().size());

        List<String> prereqCodes = detail.prerequisites().stream().map(AdminCourseService.PrerequisiteItem::prerequisiteCode).toList();
        org.junit.jupiter.api.Assertions.assertTrue(prereqCodes.contains("DBI202"));
        org.junit.jupiter.api.Assertions.assertTrue(prereqCodes.contains("PRO192"));
    }

    @Test
    void importsFlmSyllabusDatasetVerticalKeyValue() throws Exception {
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("SyllabusDetails");

        String[][] data = {
                {"Syllabus ID:", "13165"},
                {"Syllabus Name:", "Java Web Application Development_Phát triển ứng dụng Java web"},
                {"Course Name English:", "Java Web Application Development"},
                {"Subject Code:", "PRJ301"},
                {"Learning-Teaching Method:", "Project based learning,Active learning,Social Constructivism,In-class lecture"},
                {"NoCredit:", "3"},
                {"Degree Level:", "Bachelor"},
                {"Time Allocation:", "Study hour (150h) = 45h contact hours"},
                {"Pre-Requisite:", "DBI202, PRO192"},
                {"Description:", "By the end of this course Students will be able to build web apps."}
        };

        for (int i = 0; i < data.length; i++) {
            Row r = sheet.createRow(i);
            r.createCell(0).setCellValue(data[i][0]);
            r.createCell(1).setCellValue(data[i][1]);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        workbook.write(out);
        workbook.close();

        AdminCourseService.ImportPreviewResult preview = courses.previewXlsx(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(1, preview.totalRows());
        assertEquals(1, preview.validRows());
        assertEquals(0, preview.errorRows());

        AdminCourseService.ImportRowItem item = preview.items().get(0);
        assertEquals("PRJ301", item.code());
        assertEquals("Java Web Application Development", item.name());
        assertEquals(3, item.credits());
        assertEquals("DBI202, PRO192", item.prerequisites());
        assertEquals("VALID", item.status());
    }

    @Test
    void generatesSampleXlsxWorkbooksInTemporaryDirectory(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDirectory) throws Exception {
        String prjDescription = """
                By the end of this course Students will be able to:
                a) Knowledge: (what will students know?)
                • Understand the core technologies of Java web programming:
                - Servlet and JSP
                - Scope of sharing state (session, application, request,page)
                • Know how to develop and deploy your own websites using Java
                • Understand and be able to apply MVC architecture for the web
                - Learning how to apply JPA in websites using Java
                • Learning how to apply AI in your own website using Java
                b) Skills: (what will students be able to do?)
                • Basic Web application development applying MVC Design Pattern using Servlet/Filter as Controller
                • Creating a simple Web Application demo using JPA""";

        // 1. Tabular (Horizontal) format
        try (Workbook wbTabular = new XSSFWorkbook()) {
            Sheet sheet = wbTabular.createSheet("Courses");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Subject Code");
            header.createCell(1).setCellValue("Course Name English");
            header.createCell(2).setCellValue("NoCredit");
            header.createCell(3).setCellValue("Category");
            header.createCell(4).setCellValue("Pre-Requisite");
            header.createCell(5).setCellValue("Description");

            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("PRJ301");
            r1.createCell(1).setCellValue("Java Web Application Development");
            r1.createCell(2).setCellValue(3);
            r1.createCell(3).setCellValue("SPEC");
            r1.createCell(4).setCellValue("DBI202, PRO192");
            r1.createCell(5).setCellValue(prjDescription);

            java.io.File file1 = tempDirectory.resolve("courses_prj301_tabular.xlsx").toFile();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file1)) {
                wbTabular.write(fos);
            }
            org.junit.jupiter.api.Assertions.assertTrue(file1.exists());
        }

        // 2. Vertical FLM syllabus key-value format
        try (Workbook wbVertical = new XSSFWorkbook()) {
            Sheet sheet = wbVertical.createSheet("SyllabusDetails");
            String[][] data = {
                    {"Syllabus ID:", "13165"},
                    {"Syllabus Name:", "Java Web Application Development_Phát triển ứng dụng Java web"},
                    {"Course Name English:", "Java Web Application Development"},
                    {"Subject Code:", "PRJ301"},
                    {"Learning-Teaching Method:", "Project based learning,Active learning,Social Constructivism,In-class lecture"},
                    {"NoCredit:", "3"},
                    {"Degree Level:", "Bachelor"},
                    {"Time Allocation:", "Study hour (150h) = 45h contact hours + 1h final exam + 85' practical exam + 102.6h self-study"},
                    {"Pre-Requisite:", "DBI202, PRO192"},
                    {"Description:", prjDescription},
                    {"StudentTasks:", "- Attend at least 80% of contact hours\\n- Actively participate in class activities"},
                    {"Tools:", "Web server: Tomcat 9+, IDE: Netbeans 13+, Java EE: 1.8+, MS SQL Server 2019+"},
                    {"Scoring Scale:", "10"},
                    {"DecisionNo MM/dd/yyyy:", "932/QĐ-ĐHFPT dated 08/22/2025"},
                    {"IsApproved:", "True"},
                    {"Is Scored:", "True"},
                    {"MinAvgMarkToPass:", "5"},
                    {"IsActive:", "True"},
                    {"ApprovedDate:", "8/22/2025"}
            };
            for (int i = 0; i < data.length; i++) {
                Row r = sheet.createRow(i);
                r.createCell(0).setCellValue(data[i][0]);
                r.createCell(1).setCellValue(data[i][1]);
            }

            java.io.File file2 = tempDirectory.resolve("courses_prj301_flm_details.xlsx").toFile();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(file2)) {
                wbVertical.write(fos);
            }
            org.junit.jupiter.api.Assertions.assertTrue(file2.exists());
        }
    }
}

