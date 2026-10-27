package vn.edu.fpt.faaps;

import vn.edu.fpt.faaps.controller.AdminAiReportController;
import vn.edu.fpt.faaps.controller.AdminAssignmentController;
import vn.edu.fpt.faaps.controller.AdminNotificationController;
import vn.edu.fpt.faaps.controller.AdminOverviewController;
import vn.edu.fpt.faaps.controller.AssignmentWorkflowController;
import vn.edu.fpt.faaps.controller.CohortAdvisorController;
import vn.edu.fpt.faaps.controller.StudentAdvisingController;

import vn.edu.fpt.faaps.user.controller.AdminUserController;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-beans;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=none"
})
class AdminBeanRegistrationTest {
    @Autowired
    private ApplicationContext context;

    @Test
    void adminControllersExistWhenDatasourceIsConfigured() {
        assertNotNull(context.getBean(AdminUserController.class));
        assertNotNull(context.getBean(AdminAssignmentController.class));
        assertNotNull(context.getBean(AssignmentWorkflowController.class));
        assertNotNull(context.getBean(CohortAdvisorController.class));
        assertNotNull(context.getBean(StudentAdvisingController.class));
        assertNotNull(context.getBean(AdminNotificationController.class));
        assertNotNull(context.getBean(AdminOverviewController.class));
        assertNotNull(context.getBean(AdminAiReportController.class));
    }
}

