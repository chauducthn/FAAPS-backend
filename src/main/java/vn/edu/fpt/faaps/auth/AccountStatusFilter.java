package vn.edu.fpt.faaps.auth;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class AccountStatusFilter extends OncePerRequestFilter {
    private final AuthAccountStore accountStore;

    public AccountStatusFilter(AuthAccountStore accountStore) {
        this.accountStore = accountStore;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AuthSession session) {
            try {
                AuthAccount account = accountStore.findById(session.id()).orElse(null);
                if (account == null || !"ACTIVE".equals(account.status())
                        || !session.role().equals(account.role())) {
                    request.getSession().invalidate();
                    SecurityContextHolder.clearContext();
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
            } catch (AuthUnavailableException exception) {
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
