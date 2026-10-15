package vn.edu.fpt.faaps.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

public class JdbcAuthAccountStore implements AuthAccountStore {
    private final JdbcTemplate jdbcTemplate;

    public JdbcAuthAccountStore(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<AuthAccount> findById(UUID id) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, u.avatar_url, r.code AS role,
                       u.status, u.password_hash
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.id = ?
                """, this::mapAccount, id.toString()).stream().findFirst();
    }

    @Override
    public Optional<AuthAccount> findAdminByEmail(String email) {
        return jdbcTemplate.query("""
                SELECT u.id, u.email, u.full_name, u.avatar_url, r.code AS role,
                       u.status, u.password_hash
                FROM users u JOIN roles r ON r.id = u.role_id
                WHERE u.email = ? AND r.code = 'ADMIN'
                """, this::mapAccount, email).stream().findFirst();
    }

    @Override
    public AuthAccount resolveGoogleAccount(GoogleIdentity identity) {
        throw new AuthUnavailableException();
    }

    private AuthAccount mapAccount(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AuthAccount(UUID.fromString(resultSet.getString("id")), resultSet.getString("email"),
                resultSet.getString("full_name"), resultSet.getString("avatar_url"),
                resultSet.getString("role"), resultSet.getString("status"),
                resultSet.getString("password_hash"));
    }
}
