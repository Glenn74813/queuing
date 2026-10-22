package ph.gov.philhealth.qms.user;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.common.PageResponse;
import ph.gov.philhealth.qms.security.JwtAuthenticationFilter;
import ph.gov.philhealth.qms.user.UserDtos.AssignmentInput;
import ph.gov.philhealth.qms.user.UserDtos.CreateUserRequest;
import ph.gov.philhealth.qms.user.UserDtos.ResetPasswordRequest;
import ph.gov.philhealth.qms.user.UserDtos.SessionDto;
import ph.gov.philhealth.qms.user.UserDtos.RoleDto;
import ph.gov.philhealth.qms.user.UserDtos.StatusRequest;
import ph.gov.philhealth.qms.user.UserDtos.UpdateUserRequest;
import ph.gov.philhealth.qms.user.UserDtos.UserDto;

@RestController
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/roles")
    @PreAuthorize("hasAnyAuthority('USER_VIEW','USER_MANAGE')")
    public List<RoleDto> roles() {
        return service.roles();
    }

    @GetMapping("/api/v1/users")
    @PreAuthorize("hasAuthority('USER_VIEW')")
    public PageResponse<UserDto> search(@RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.search(q, page, size);
    }

    @PostMapping("/api/v1/users")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserDto create(@Valid @RequestBody CreateUserRequest req) {
        return service.create(req);
    }

    @PutMapping("/api/v1/users/{id}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserDto update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/api/v1/users/{id}/status")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserDto status(@PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        return service.setStatus(id, req.status(), req.reason());
    }

    @PutMapping("/api/v1/users/{id}/assignments")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public UserDto assignments(@PathVariable UUID id, @Valid @RequestBody List<@Valid AssignmentInput> req) {
        return service.replaceAssignments(id, req);
    }

    @GetMapping("/api/v1/users/{id}/sessions")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public List<SessionDto> sessions(@PathVariable UUID id, HttpServletRequest http) {
        return service.sessions(id, (UUID) http.getAttribute(JwtAuthenticationFilter.SESSION_ATTRIBUTE));
    }

    @DeleteMapping("/api/v1/users/{id}/sessions/{sessionId}")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ResponseEntity<Void> revokeSession(@PathVariable UUID id, @PathVariable UUID sessionId) {
        service.revokeSessions(id, sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/api/v1/users/{id}/sessions")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ResponseEntity<Void> revokeAllSessions(@PathVariable UUID id) {
        service.revokeSessions(id, null);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/users/{id}/reset-password")
    @PreAuthorize("hasAuthority('USER_MANAGE')")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID id, @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.newPassword());
        return ResponseEntity.noContent().build();
    }
}
