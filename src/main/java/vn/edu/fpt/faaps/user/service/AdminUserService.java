package vn.edu.fpt.faaps.user.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.edu.fpt.faaps.infrastructure.mail.EmailService;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleItem;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.CreateAccount;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.EmailCheckItem;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Page;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.PasswordResetResponse;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;

@Service
public class AdminUserService {

    private final JdbcTemplate jdbc;
    private final AdminUserRepository repository;
    private final UserRoleService roles;
    private final AdminUserCreationHelper creationHelper;
    private final AdminPasswordResetHelper passwordResetHelper;

    @Autowired
    public AdminUserService(JdbcTemplate jdbc, AdminUserRepository repository, UserRoleService roles,
            PasswordEncoder passwordEncoder, @Autowired(required = false) EmailService emailService) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.roles = roles;
        this.creationHelper = new AdminUserCreationHelper(jdbc);
        this.passwordResetHelper = new AdminPasswordResetHelper(jdbc, passwordEncoder, emailService);
    }

    public AdminUserService(JdbcTemplate jdbc, AdminUserRepository repository, UserRoleService roles,
            PasswordEncoder passwordEncoder) {
        this(jdbc, repository, roles, passwordEncoder, null);
    }

    public AdminUserService(JdbcTemplate jdbc, AdminUserRepository repository, UserRoleService roles) {
        this(jdbc, repository, roles, new BCryptPasswordEncoder(), null);
    }

    public Page list(String role, String status, String search, UUID majorId, int page, int size, String sort) {
        return list(role, status, search, majorId, null, page, size, sort);
    }

    public Page list(String role, String status, String search, UUID majorId, UUID curriculumVersionId, int page,
            int size, String sort) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        if (role != null && !List.of("STUDENT", "ADVISOR").contains(role)) {
            throw new IllegalArgumentException("role is invalid");
        }
        if (status != null && !List.of("ACTIVE", "INACTIVE", "SUSPENDED", "LOCKED").contains(status)) {
            throw new IllegalArgumentException("status is invalid");
        }
        return repository.list(role, status, search, majorId, curriculumVersionId, page, size, sort);
    }

    public Detail detail(UUID id) {
        return repository.detail(id);
    }

    @Transactional
    public Detail create(CreateAccount input) {
        UUID userId = creationHelper.createAccount(input);
        return detail(userId);
    }

    @Transactional
    public int changeStatus(List<UUID> userIds, String status, UUID actorId) {
        if (status == null || !List.of("ACTIVE", "SUSPENDED").contains(status)) {
            throw new IllegalArgumentException("status must be ACTIVE or SUSPENDED");
        }
        validateUserIdBatch(userIds);
        if ("SUSPENDED".equals(status) && userIds.contains(actorId)) {
            throw new Conflict();
        }
        int changed = 0;
        for (UUID id : userIds.stream().sorted().toList()) {
            List<String> current = jdbc.query("""
                    SELECT u.status FROM users u JOIN roles r ON r.id = u.role_id
                    WHERE u.id = ? AND r.code IN ('STUDENT', 'ADVISOR') FOR UPDATE
                    """, (result, row) -> result.getString(1), id.toString());
            if (current.isEmpty()) {
                throw new NotFound();
            }
            if (status.equals(current.get(0))) {
                continue;
            }
            if (!("ACTIVE".equals(current.get(0)) && "SUSPENDED".equals(status))
                    && !("SUSPENDED".equals(current.get(0)) && "ACTIVE".equals(status))) {
                throw new Conflict();
            }
            changed += jdbc.update("UPDATE users SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                    status, id.toString());
        }
        return changed;
    }

    @Transactional
    public Detail changeRole(UUID userId, ChangeRole input) {
        return roles.change(userId, input);
    }

    @Transactional
    public BulkRoleResponse bulkChangeRole(String targetRole, List<BulkRoleItem> items) {
        return roles.bulkChange(targetRole, items);
    }

    public Map<String, Boolean> checkEmailAvailability(List<EmailCheckItem> items) {
        return roles.checkEmailAvailability(items);
    }

    @Transactional
    public PasswordResetResponse resetPassword(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        return passwordResetHelper.resetPasswords(List.of(id));
    }

    @Transactional
    public PasswordResetResponse bulkResetPassword(List<UUID> userIds) {
        validateUserIdBatch(userIds);
        return passwordResetHelper.resetPasswords(userIds);
    }

    private void validateUserIdBatch(List<UUID> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.size() > 100
                || userIds.stream().anyMatch(Objects::isNull)
                || userIds.stream().distinct().count() != userIds.size()) {
            throw new IllegalArgumentException("userIds must contain 1 to 100 unique, non-null users");
        }
    }
}
