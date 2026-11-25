package vn.edu.fpt.faaps.user.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleEntry;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleItem;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.exception.UserExceptions.NotFound;

final class UserBulkRoleHelper {

    private final UserRoleService roleService;

    UserBulkRoleHelper(UserRoleService roleService) {
        this.roleService = roleService;
    }

    BulkRoleResponse bulkChange(String targetRole, List<BulkRoleItem> items) {
        if (targetRole == null || !List.of("STUDENT", "ADVISOR").contains(targetRole)) {
            throw new IllegalArgumentException("role must be STUDENT or ADVISOR");
        }
        if (items == null || items.isEmpty() || items.size() > 100) {
            throw new IllegalArgumentException("items must contain 1 to 100 accounts");
        }
        Set<String> seenEmails = new HashSet<>();
        List<BulkRoleEntry> results = new ArrayList<>();
        int updated = 0;
        for (BulkRoleItem item : items) {
            BulkRoleEntry entry = applyItem(targetRole, item, seenEmails);
            if ("UPDATED".equals(entry.status())) {
                updated++;
            }
            results.add(entry);
        }
        return new BulkRoleResponse(updated, results);
    }

    private BulkRoleEntry applyItem(String targetRole, BulkRoleItem item, Set<String> seenEmails) {
        if (item == null || item.userId() == null) {
            return new BulkRoleEntry(null, null, null, "SKIPPED", "Missing userId");
        }
        String normalizedEmail = item.email() == null || item.email().isBlank()
                ? null : item.email().strip().toLowerCase(Locale.ROOT);
        if (normalizedEmail != null && !seenEmails.add(normalizedEmail)) {
            return new BulkRoleEntry(item.userId(), normalizedEmail, null, "CONFLICT",
                    "Duplicate email within this batch");
        }
        try {
            Detail detail = roleService.change(item.userId(), new ChangeRole(targetRole, normalizedEmail,
                    item.studentCode(), null, item.curriculumVersionId(),
                    item.enrollmentSemesterId(), item.maxAdvisees()));
            return new BulkRoleEntry(detail.id(), detail.email(), detail.fullName(), "UPDATED", null);
        } catch (Conflict | IllegalArgumentException | NotFound ex) {
            return new BulkRoleEntry(item.userId(), normalizedEmail, null, "CONFLICT", ex.getMessage());
        }
    }
}
