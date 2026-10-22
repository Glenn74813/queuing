package ph.gov.philhealth.qms.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.LoginRateLimiter;
import ph.gov.philhealth.qms.user.UserDtos.ChangePasswordRequest;
import ph.gov.philhealth.qms.user.UserDtos.LoginRequest;
import ph.gov.philhealth.qms.user.UserDtos.Me;
import ph.gov.philhealth.qms.user.UserDtos.RefreshRequest;
import ph.gov.philhealth.qms.user.UserDtos.TokenResponse;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final LoginRateLimiter limiter;

    public AuthController(AuthService auth, LoginRateLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        limiter.check(http.getRemoteAddr());
        return auth.login(req.username(), req.password(), client(http));
    }

    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
        limiter.check(http.getRemoteAddr());
        return auth.refresh(req.refreshToken(), client(http));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        auth.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static AuthService.ClientInfo client(HttpServletRequest http) {
        return new AuthService.ClientInfo(http.getRemoteAddr(), http.getHeader("User-Agent"));
    }

    @GetMapping("/me")
    public Me me() {
        return auth.me(CurrentUser.get().id());
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(CurrentUser.get(), req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }
}
