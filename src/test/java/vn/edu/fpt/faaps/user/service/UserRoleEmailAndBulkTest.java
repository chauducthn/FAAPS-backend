package vn.edu.fpt.faaps.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleItem;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.BulkRoleResponse;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.ChangeRole;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.Detail;
import vn.edu.fpt.faaps.user.dto.AdminUserDtos.EmailCheckItem;
import vn.edu.fpt.faaps.user.exception.UserExceptions.Conflict;
import vn.edu.fpt.faaps.user.repository.AdminUserRepository;

class UserRoleEmailAndBulkTest {

    private JdbcTemplate jdbc;
    private AdminUserRepository users;
    private UserRoleService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AdminUserRepository.class);
        service = new UserRoleService(jdbc, users);
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeWithValidReplacementEmailUpdatesEmailAndClearsExternalId() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), eq(id.toString())))
                .thenReturn(List.of("STUDENT"));
        when(jdbc.queryForObject(contains("LOWER(email) = ? AND id <> ?"), eq(Integer.class),
                eq("lan@fe.edu.vn"), eq(id.toString()))).thenReturn(0);
        when(jdbc.update(contains("external_id=NULL"), eq("ADVISOR"), eq("lan@fe.edu.vn"), eq(id.toString())))
                .thenReturn(1);
        Detail expected = new Detail(id, "ADV01", "Lan", "lan@fe.edu.vn", "ADVISOR", "ACTIVE",
                null, null, null, null, null, null, null, null, null, null);
        when(users.detail(id)).thenReturn(expected);

        Detail actual = service.change(id, new ChangeRole("ADVISOR", "LAN@FE.EDU.VN",
                null, null, null, null, null));

        assertThat(actual.email()).isEqualTo("lan@fe.edu.vn");
        verify(jdbc).update(contains("external_id=NULL"), eq("ADVISOR"), eq("lan@fe.edu.vn"), eq(id.toString()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeRejectsReplacementEmailWithWrongDomain() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), eq(id.toString())))
                .thenReturn(List.of("STUDENT"));

        assertThatThrownBy(() -> service.change(id, new ChangeRole("ADVISOR", "lan@fpt.edu.vn",
                null, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("@fe.edu.vn");
    }

    @Test
    @SuppressWarnings("unchecked")
    void changeRejectsDuplicateEmailOwnedByAnotherUser() {
        UUID id = UUID.randomUUID();
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), eq(id.toString())))
                .thenReturn(List.of("STUDENT"));
        when(jdbc.queryForObject(contains("LOWER(email) = ? AND id <> ?"), eq(Integer.class),
                eq("taken@fe.edu.vn"), eq(id.toString()))).thenReturn(1);

        assertThatThrownBy(() -> service.change(id, new ChangeRole("ADVISOR", "taken@fe.edu.vn",
                null, null, null, null, null)))
                .isInstanceOf(Conflict.class)
                .hasMessageContaining("already in use");
    }

    @Test
    void checkEmailAvailabilityReturnsAvailabilityPerEmail() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(jdbc.queryForObject(contains("LOWER(email) = ? AND id <> ?"), eq(Integer.class),
                eq("free@fe.edu.vn"), eq(u1.toString()))).thenReturn(0);
        when(jdbc.queryForObject(contains("LOWER(email) = ? AND id <> ?"), eq(Integer.class),
                eq("used@fe.edu.vn"), eq(u2.toString()))).thenReturn(1);

        Map<String, Boolean> result = service.checkEmailAvailability(List.of(
                new EmailCheckItem(u1, "FREE@FE.EDU.VN"),
                new EmailCheckItem(u2, "used@fe.edu.vn")));

        assertThat(result).containsEntry("free@fe.edu.vn", true).containsEntry("used@fe.edu.vn", false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void bulkChangeFlagsInBatchDuplicateEmailsAndAppliesValidRows() {
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), eq(u1.toString())))
                .thenReturn(List.of("STUDENT"));
        when(jdbc.queryForObject(contains("LOWER(email) = ? AND id <> ?"), eq(Integer.class),
                eq("same@fe.edu.vn"), eq(u1.toString()))).thenReturn(0);
        when(jdbc.update(contains("external_id=NULL"), eq("ADVISOR"), eq("same@fe.edu.vn"), eq(u1.toString())))
                .thenReturn(1);
        when(users.detail(u1)).thenReturn(new Detail(u1, "ADV01", "User One", "same@fe.edu.vn", "ADVISOR", "ACTIVE",
                null, null, null, null, null, null, null, null, null, null));

        BulkRoleResponse response = service.bulkChange("ADVISOR", List.of(
                new BulkRoleItem(u1, "same@fe.edu.vn", null, null, null, null),
                new BulkRoleItem(u2, "SAME@FE.EDU.VN", null, null, null, null)));

        assertThat(response.updated()).isEqualTo(1);
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).status()).isEqualTo("UPDATED");
        assertThat(response.results().get(1).status()).isEqualTo("CONFLICT");
        assertThat(response.results().get(1).reason()).contains("Duplicate email within this batch");
        verify(jdbc).update(anyString(), eq("ADVISOR"), eq("same@fe.edu.vn"), eq(u1.toString()));
    }
}
