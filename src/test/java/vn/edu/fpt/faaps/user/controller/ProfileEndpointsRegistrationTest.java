package vn.edu.fpt.faaps.user.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The profile endpoints must be registered, otherwise the frontend gets a 404 "No handler found" that looks like a
 * broken route instead of a missing bean.
 */
@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:profile-beans;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=none"
})
class ProfileEndpointsRegistrationTest {
    @Autowired
    private ApplicationContext context;

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    void profileControllersExistWhenDatasourceIsConfigured() {
        assertNotNull(context.getBean(UserProfileController.class));
    }

    @Test
    void theSharedProfileEndpointIsMapped() {
        boolean mapped = mappings.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition() == null
                        ? java.util.stream.Stream.<String>empty()
                        : info.getPathPatternsCondition().getPatternValues().stream())
                .anyMatch(pattern -> pattern.equals("/api/profile"));
        assertEquals(true, mapped, "GET/PUT /api/profile must be mapped");
    }
}
