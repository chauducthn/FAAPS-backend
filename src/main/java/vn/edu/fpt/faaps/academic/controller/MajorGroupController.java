package vn.edu.fpt.faaps.academic.controller;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/major-groups", "/major-groups"})
public class MajorGroupController {
    private final JdbcTemplate jdbc;

    public MajorGroupController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public List<MajorGroupItem> list() {
        return jdbc.query("SELECT id, code, name FROM major_groups ORDER BY code ASC, name ASC",
                (rs, row) -> new MajorGroupItem(rs.getString("id"), rs.getString("code"), rs.getString("name")));
    }

    public record MajorGroupItem(String id, String code, String name) {}
}
