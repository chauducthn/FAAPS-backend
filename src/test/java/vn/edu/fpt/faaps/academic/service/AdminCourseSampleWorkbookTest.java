package vn.edu.fpt.faaps.academic.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.edu.fpt.faaps.academic.service.AdminCourseTestSupport.PRJ_DESCRIPTION;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdminCourseSampleWorkbookTest {

    @Test
    void generatesSampleXlsxWorkbooksInTemporaryDirectory(@TempDir Path tempDirectory) throws Exception {
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
            r1.createCell(5).setCellValue(PRJ_DESCRIPTION);

            File file1 = tempDirectory.resolve("courses_prj301_tabular.xlsx").toFile();
            try (FileOutputStream fos = new FileOutputStream(file1)) {
                wbTabular.write(fos);
            }
            assertTrue(file1.exists());
        }

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
                    {"Description:", PRJ_DESCRIPTION},
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

            File file2 = tempDirectory.resolve("courses_prj301_flm_details.xlsx").toFile();
            try (FileOutputStream fos = new FileOutputStream(file2)) {
                wbVertical.write(fos);
            }
            assertTrue(file2.exists());
        }
    }
}
