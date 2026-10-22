package ph.gov.philhealth.qms.user;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.common.PageResponse;
import ph.gov.philhealth.qms.org.Region;
import ph.gov.philhealth.qms.org.RegionRepository;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.org.SiteRepository;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;
import ph.gov.philhealth.qms.security.ScopeType;
import ph.gov.philhealth.qms.user.UserDtos.SessionDto;
import ph.gov.philhealth.qms.user.UserDtos.AssignmentInput;
import ph.gov.philhealth.qms.user.UserDtos.CreateUserRequest;
import ph.gov.philhealth.qms.user.UserDtos.RoleDto;
import ph.gov.philhealth.qms.user.UserDtos.UpdateUserRequest;
import ph.gov.philhealth.qms.user.UserDtos.UserDto;

/**
 * Hierarchical user administration. An administrator may only manage users whose every role
 * assignment lies inside the administrator's own USER_MANAGE scope, and may only grant roles of a
 * lower level than their own highest role (Super Administrators may grant any role).
 */
@Service
public class UserService {

    private static final String SUPER_ADMIN = "SUPER_ADMIN";

    private final AppUserRepository users;
    private final AppRoleRepository roles;
    private final RegionRepository regions;
    private final SiteRepository sites;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder encoder;
    private final ScopeService scope;
    private final AuditService audit;

    public UserService(AppUserRepository users, AppRoleRepository roles, RegionRepository regions,
                       SiteRepository sites, RefreshTokenRepository refreshTokens, PasswordEncoder encoder,
                       ScopeService scope, AuditService audit) {
        this.users = users;
        this.roles = roles;
        this.regions = regions;
        this.sites = sites;
        this.refreshTokens = refreshTokens;
        this.encoder = encoder;
        this.scope = scope;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<RoleDto> roles() {
        return roles.findAllByOrderByLevelDesc().stream()
                .map(r -> new RoleDto(r.getCode(), r.getName(), r.getScopeType(), r.getLevel(), r.getPermissions()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<UserDto> search(String q, int page, int size) {
        AuthUser actor = CurrentUser.get();
        Set<UUID> siteIds = scope.accessibleSiteIds(Permission.USER_VIEW);
        boolean national = siteIds == null;
        List<UUID> regionIds = actor.grants().stream()
                .filter(g -> g.permissions().contains(Permission.USER_VIEW) && g.scope() == ScopeType.REGION)
                .map(AuthUser.Grant::regionId).toList();
        String pattern = q == null || q.isBlank() ? "" : "%" + q.trim().toLowerCase() + "%";
        var result = users.search(pattern, national, nonEmpty(national ? List.of() : new ArrayList<>(siteIds)),
                nonEmpty(regionIds), PageResponse.request(page, size, Sort.by("username")));
        return PageResponse.of(result, UserDto::of);
    }

    @Transactional
    public UserDto create(CreateUserRequest req) {
        AuthUser actor = CurrentUser.get();
        if (users.findByUsernameIgnoreCase(req.username()).isPresent()) {
            throw ApiException.conflict("Username already exists");
        }
        PasswordPolicy.validate(req.password(), req.username());
        AppUser u = new AppUser();
        u.setId(Ids.newId());
        u.setUsername(req.username().trim());
        u.setFullName(req.fullName().trim());
        u.setEmail(req.email());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setStatus(AppUser.Status.ACTIVE);
        u.setMustChangePassword(true);
        u.getAssignments().addAll(buildAssignments(actor, u, req.assignments()));
        users.save(u);
        audit.record("USER_CREATED", "AppUser", u.getId(), firstSite(u), describe(u));
        return UserDto.of(u);
    }

    @Transactional
    public UserDto update(UUID id, UpdateUserRequest req) {
        AppUser u = manageable(id);
        u.setFullName(req.fullName().trim());
        u.setEmail(req.email());
        audit.record("USER_UPDATED", "AppUser", u.getId(), firstSite(u), u.getFullName());
        return UserDto.of(u);
    }

    @Transactional
    public UserDto setStatus(UUID id, AppUser.Status status, String reason) {
        AuthUser actor = CurrentUser.get();
        if (actor.id().equals(id)) {
            throw ApiException.forbidden("You cannot change your own account status");
        }
        AppUser u = manageable(id);
        AppUser.Status before = u.getStatus();
        u.setStatus(status);
        if (status != AppUser.Status.ACTIVE) {
            refreshTokens.revokeAllForUser(u.getId(), Instant.now(), "REVOKED");
        } else {
            u.setLockedUntil(null);
            u.setFailedAttempts(0);
        }
        audit.record("USER_STATUS_CHANGED", "AppUser", u.getId(), firstSite(u),
                before + " -> " + status + ": " + reason);
        return UserDto.of(u);
    }

    @Transactional
    public UserDto replaceAssignments(UUID id, List<AssignmentInput> inputs) {
        AuthUser actor = CurrentUser.get();
        if (actor.id().equals(id)) {
            throw ApiException.forbidden("You cannot change your own roles");
        }
        if (inputs.isEmpty()) {
            throw ApiException.badRequest("At least one role assignment is required");
        }
        AppUser u = manageable(id);
        String before = describe(u);
        List<RoleAssignment> next = buildAssignments(actor, u, inputs);
        u.getAssignments().clear();
        users.flush();
        u.getAssignments().addAll(next);
        refreshTokens.revokeAllForUser(u.getId(), Instant.now(), "REVOKED");
        audit.record("USER_ROLES_CHANGED", "AppUser", u.getId(), firstSite(u), "before=" + before + " after=" + describe(u));
        return UserDto.of(u);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        AppUser u = manageable(id);
        PasswordPolicy.validate(newPassword, u.getUsername());
        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(true);
        u.setFailedAttempts(0);
        u.setLockedUntil(null);
        refreshTokens.revokeAllForUser(u.getId(), Instant.now(), "REVOKED");
        audit.record("USER_PASSWORD_RESET", "AppUser", u.getId(), firstSite(u), null);
    }

    @Transactional(readOnly = true)
    public List<SessionDto> sessions(UUID id, UUID currentSessionId) {
        AppUser u = manageable(id);
        return refreshTokens.findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderBySessionStartedAtDesc(u.getId(),
                        Instant.now()).stream()
                .map(t -> new SessionDto(t.getSessionId(), t.getSessionStartedAt(), t.getCreatedAt(), t.getExpiresAt(),
                        t.getIpAddress(), t.getUserAgent(), t.getSessionId().equals(currentSessionId)))
                .toList();
    }

    /** Ends one sign-in session, or all of them when {@code sessionId} is null; their access tokens stop at once. */
    @Transactional
    public int revokeSessions(UUID id, UUID sessionId) {
        AppUser u = manageable(id);
        Instant now = Instant.now();
        int n = sessionId != null ? refreshTokens.revokeSession(u.getId(), sessionId, now, "REVOKED")
                : refreshTokens.revokeAllForUser(u.getId(), now, "REVOKED");
        if (sessionId != null && n == 0) {
            throw ApiException.notFound("Session");
        }
        audit.record("USER_SESSIONS_REVOKED", "AppUser", u.getId(), firstSite(u),
                sessionId != null ? "session " + sessionId : "all sessions (" + n + ")");
        return n;
    }

    /** Loads a user the current actor is allowed to administer. */
    private AppUser manageable(UUID id) {
        AuthUser actor = CurrentUser.get();
        AppUser u = users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
        for (RoleAssignment a : u.getAssignments()) {
            requireCanGrant(actor, a.getRole(), a.getRegion(), a.getSite());
        }
        return u;
    }

    private List<RoleAssignment> buildAssignments(AuthUser actor, AppUser user, List<AssignmentInput> inputs) {
        List<RoleAssignment> result = new ArrayList<>();
        for (AssignmentInput in : inputs) {
            AppRole role = roles.findById(in.roleCode()).orElseThrow(() -> ApiException.badRequest("Unknown role " + in.roleCode()));
            Region region = in.regionId() != null ? regions.findById(in.regionId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown region")) : null;
            Site site = in.siteId() != null ? sites.findById(in.siteId())
                    .orElseThrow(() -> ApiException.badRequest("Unknown site")) : null;
            validateShape(role, region, site);
            requireCanGrant(actor, role, region, site);
            RoleAssignment a = new RoleAssignment();
            a.setId(Ids.newId());
            a.setUser(user);
            a.setRole(role);
            a.setRegion(region);
            a.setSite(site);
            result.add(a);
        }
        return result;
    }

    private static void validateShape(AppRole role, Region region, Site site) {
        switch (role.getScopeType()) {
            case NATIONAL -> {
                if (region != null || site != null) {
                    throw ApiException.badRequest(role.getName() + " is a national role; do not set a region or site");
                }
            }
            case REGION -> {
                if (region != null && site != null) {
                    throw ApiException.badRequest("Set either a region or a site, not both");
                }
            }
            case SITE -> {
                if (site == null || region != null) {
                    throw ApiException.badRequest(role.getName() + " must be assigned to a site");
                }
            }
        }
    }

    private void requireCanGrant(AuthUser actor, AppRole role, Region region, Site site) {
        boolean superAdmin = actor.grants().stream().anyMatch(g -> SUPER_ADMIN.equals(g.roleCode()));
        if (!superAdmin && role.getLevel() >= actor.maxLevel()) {
            throw ApiException.forbidden("You cannot manage users holding role " + role.getName());
        }
        boolean covered;
        if (site != null) {
            covered = actor.canAccessSite(Permission.USER_MANAGE, site.getId(), site.getRegionId());
        } else if (region != null) {
            covered = actor.grants().stream().anyMatch(g -> g.permissions().contains(Permission.USER_MANAGE)
                    && (g.scope() == ScopeType.NATIONAL
                        || (g.scope() == ScopeType.REGION && region.getId().equals(g.regionId()))));
        } else {
            covered = actor.hasNational(Permission.USER_MANAGE);
        }
        if (!covered) {
            throw ApiException.forbidden("Outside your user-management scope");
        }
    }

    private static UUID firstSite(AppUser u) {
        return u.getAssignments().stream().filter(a -> a.getSite() != null).map(a -> a.getSite().getId())
                .findFirst().orElse(null);
    }

    private static String describe(AppUser u) {
        return u.getUsername() + " " + u.getAssignments().stream()
                .map(a -> a.getRole().getCode() + "@" + (a.getSite() != null ? a.getSite().getCode()
                        : a.getRegion() != null ? a.getRegion().getCode() : "NATIONAL"))
                .toList();
    }

    private static List<UUID> nonEmpty(List<UUID> ids) {
        // JPQL "in ()" is invalid; a random id never matches.
        return ids.isEmpty() ? List.of(UUID.randomUUID()) : ids;
    }
}
