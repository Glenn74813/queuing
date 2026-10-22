package ph.gov.philhealth.qms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import ph.gov.philhealth.qms.common.GlobalExceptionHandler;

/** Users with a temporary (admin-set) password may only change it, read their profile, or log out. */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    private static final Set<String> ALLOWED = Set.of("/api/v1/auth/me", "/api/v1/auth/change-password",
            "/api/v1/auth/logout");

    private final ObjectMapper mapper;

    public PasswordChangeRequiredFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var user = CurrentUser.find();
        if (user.isPresent() && user.get().mustChangePassword() && !ALLOWED.contains(request.getRequestURI())) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), GlobalExceptionHandler.body(HttpStatus.FORBIDDEN,
                    "PASSWORD_CHANGE_REQUIRED", "You must change your password first", request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
    }
}
