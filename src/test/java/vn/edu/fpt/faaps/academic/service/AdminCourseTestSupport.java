package vn.edu.fpt.faaps.academic.service;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

final class AdminCourseTestSupport {

    static final String PRJ_DESCRIPTION = """
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

    record Fixture(JdbcTemplate jdbc, AdminCourseService courses, UUID catGeneral, UUID catSpecialized) {
    }

    private AdminCourseTestSupport() {
    }

    static Fixture createFixture() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:admin-courses-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE course_categories (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(100) NOT NULL, description TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE courses (
                    id VARCHAR(36) PRIMARY KEY, code VARCHAR(50) UNIQUE NOT NULL,
                    name VARCHAR(255) NOT NULL, credits INT NOT NULL,
                    category_id VARCHAR(36) NOT NULL, description TEXT,
                    objectives TEXT, learning_outcomes TEXT, material_link VARCHAR(500),
                    status VARCHAR(20) DEFAULT 'ACTIVE',
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE course_prerequisites (
                    id VARCHAR(36) PRIMARY KEY, course_id VARCHAR(36) NOT NULL,
                    prerequisite_course_id VARCHAR(36) NOT NULL, group_no INT DEFAULT 1,
                    type VARCHAR(20) DEFAULT 'PREREQUISITE', minimum_score DECIMAL(4,2),
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbc.execute("""
                CREATE TABLE curriculum_courses (
                    id VARCHAR(36) PRIMARY KEY, curriculum_version_id VARCHAR(36),
                    course_id VARCHAR(36)
                )
                """);

        UUID catGeneral = UUID.randomUUID();
        UUID catSpecialized = UUID.randomUUID();
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'GEN', 'General Education')",
                catGeneral.toString());
        jdbc.update("INSERT INTO course_categories (id, code, name) VALUES (?, 'SPEC', 'Specialized Knowledge')",
                catSpecialized.toString());
        return new Fixture(jdbc, new AdminCourseService(jdbc), catGeneral, catSpecialized);
    }
}
