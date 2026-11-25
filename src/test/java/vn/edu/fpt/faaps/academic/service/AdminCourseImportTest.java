package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.edu.fpt.faaps.academic.service.AdminCourseTestSupport.PRJ_DESCRIPTION;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminCourseImportTest {
    private AdminCourseService courses;
    private UUID catGeneral;

    @BeforeEach
    void setUp() {
        AdminCourseTestSupport.Fixture fixture = AdminCourseTestSupport.createFixture();
        courses = fixture.courses();
        catGeneral = fixture.catGeneral();
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
        assertEquals("ERROR", preview.items().get(1).status());

        int confirmed = courses.confirmImport(List.of(new AdminCourseService.ImportConfirmItem(
                validRow.code(), validRow.name(), validRow.credits(),
                UUID.fromString(validRow.categoryId()), validRow.description())));
        assertEquals(1, confirmed);
        assertEquals(1, courses.list("CSD201", null, null, 0, 10, null).totalElements());
    }

    @Test
    void importsFlmSyllabusDatasetHorizontal() throws Exception {
        courses.create("DBI202", "Introduction to Databases", 3, catGeneral, "Database fundamentals", null, null, "ACTIVE", List.of());
        courses.create("PRO192", "Object-Oriented Programming", 3, catGeneral, "Java fundamentals", null, null, "ACTIVE", List.of());

        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet("Syllabus");
        Row header = sheet.createRow(0);
        String[] headers = {"Syllabus ID", "Syllabus Name", "Course Name English", "Subject Code",
                "Learning-Teaching Method", "NoCredit", "Degree Level", "Time Allocation", "Pre-Requisite", "Description"};
        for (int i = 0; i < headers.length; i++) {
            header.createCell(i).setCellValue(headers[i]);
        }

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
        row.createCell(9).setCellValue(PRJ_DESCRIPTION);

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
                item.code(), item.name(), item.credits(), UUID.fromString(item.categoryId()),
                item.description(), item.prerequisites())));
        assertEquals(1, confirmed);

        AdminCourseService.Page listPage = courses.list("PRJ301", null, null, 0, 10, null);
        assertEquals(1, listPage.totalElements());
        AdminCourseService.CourseDetail detail = courses.detail(listPage.items().get(0).id());
        assertEquals("PRJ301", detail.code());
        assertEquals("Java Web Application Development", detail.name());
        assertEquals(3, detail.credits());
        assertEquals(2, detail.prerequisites().size());

        List<String> prereqCodes = detail.prerequisites().stream()
                .map(AdminCourseService.PrerequisiteItem::prerequisiteCode).toList();
        assertTrue(prereqCodes.contains("DBI202"));
        assertTrue(prereqCodes.contains("PRO192"));
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
}
