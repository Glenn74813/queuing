package ph.gov.philhealth.qms.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeType;

public final class UserDtos {

    private UserDtos() {
    }

    public record LoginRequest(@NotBlank @Size(max = 60) String username, @NotBlank @Size(max = 128) String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record AssignmentDto(String roleCode, String roleName, ScopeType scopeType, UUID regionId,
                                String regionName, UUID siteId, String siteName) {
        static AssignmentDto of(RoleAssignment a) {
            ScopeType scope = a.getSite() != null ? ScopeType.SITE
                    : a.getRegion() != null ? ScopeType.REGION : ScopeType.NATIONAL;
            return new AssignmentDto(a.getRole().getCode(), a.getRole().getName(), scope,
                    a.getRegion() != null ? a.getRegion().getId() : null,
                    a.getRegion() != null ? a.getRegion().getName() : null,
                    a.getSite() != null ? a.getSite().getId() : null,
                    a.getSite() != null ? a.getSite().getName() : null);
        }
    }

    public record SiteRef(UUID id, String code, String name) {
    }

    public record Me(UUID id, String username, String fullName, boolean mustChangePassword,
                     Set<Permission> permissions, List<AssignmentDto> assignments, List<SiteRef> sites) {
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, Me user) {
    }

    public record UserDto(UUID id, String username, String fullName, String email, AppUser.Status status,
                          Instant lockedUntil, Instant lastLoginAt, boolean mustChangePassword,
                          List<AssignmentDto> assignments) {
        public static UserDto of(AppUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getStatus(),
                    u.getLockedUntil(), u.getLastLoginAt(), u.isMustChangePassword(),
                    u.getAssignments().stream().map(AssignmentDto::of).toList());
        }
    }

    public record AssignmentInput(@NotBlank String roleCode, UUID regionId, UUID siteId) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "^[A-Za-z0-9._-]+$") String username,
            @NotBlank @Size(max = 150) String fullName,
            @Email @Size(max = 150) String email,
            @NotBlank String password,
            @NotEmpty List<@Valid AssignmentInput> assignments) {
    }

    public record UpdateUserRequest(@NotBlank @Size(max = 150) String fullName, @Email @Size(max = 150) String email) {
    }

    public record StatusRequest(@NotNull AppUser.Status status, @NotBlank @Size(max = 500) String reason) {
    }

    public record ResetPasswordRequest(@NotBlank String newPassword) {
    }

    public record RoleDto(String code, String name, ScopeType scopeType, int level, Set<Permission> permissions) {
    }
}
