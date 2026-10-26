package vn.edu.fpt.faaps.admin.controller;

import vn.edu.fpt.faaps.admin.service.AdminCourseService;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/course-categories")
public class AdminCourseCategoryController {
    private final AdminCourseService courses;

    public AdminCourseCategoryController(AdminCourseService courses) {
        this.courses = courses;
    }

    @GetMapping
    public List<AdminCourseService.CategoryItem> list() {
        return courses.categories();
    }
}
