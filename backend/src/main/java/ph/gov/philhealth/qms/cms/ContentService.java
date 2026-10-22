package ph.gov.philhealth.qms.cms;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.cms.ContentDtos.ContentDto;
import ph.gov.philhealth.qms.cms.ContentDtos.ContentInput;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.TimeService;
import ph.gov.philhealth.qms.org.Site;
import ph.gov.philhealth.qms.security.AuthUser;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

/**
 * Content workflow: DRAFT → PENDING_APPROVAL → PUBLISHED (or REJECTED → edit → resubmit) → ARCHIVED.
 * Approval requires CONTENT_APPROVE for the target scope and must be done by someone other than the author,
 * except that a Super Administrator may approve their own content (QMS_CMS_SUPER_ADMIN_SELF_APPROVAL,
 * default on; every such approval is audited as CONTENT_SELF_APPROVED).
 */
@Service
public class ContentService {

    private final ContentRepository repo;
    private final MediaStorage storage;
    private final ScopeService scope;
    private final AuditService audit;
    private final TimeService time;
    /** Super Administrators may publish their own content (audited); everyone else needs a second person. */
    private final boolean superAdminSelfApproval;

    public ContentService(ContentRepository repo, MediaStorage storage, ScopeService scope, AuditService audit,
                          TimeService time,
                          @org.springframework.beans.factory.annotation.Value("${qms.cms.super-admin-self-approval:true}")
                          boolean superAdminSelfApproval) {
        this.repo = repo;
        this.storage = storage;
        this.scope = scope;
        this.audit = audit;
        this.time = time;
        this.superAdminSelfApproval = superAdminSelfApproval;
    }

    @Transactional(readOnly = true)
    public List<ContentDto> activeFor(Site site) {
        return repo.findActive(site.getId(), site.getRegionId(), time.now()).stream().map(ContentDto::of).toList();
    }

    @Transactional(readOnly = true)
    public List<ContentDto> list(UUID siteId, ContentItem.Status status) {
        AuthUser user = CurrentUser.get();
        Set<UUID> sites = scope.accessibleSiteIds(Permission.CONTENT_EDIT);
        Set<UUID> approvable = scope.accessibleSiteIds(Permission.CONTENT_APPROVE);
        return repo.findAllByOrderByUpdatedAtDesc().stream()
                .filter(c -> status == null || c.getStatus() == status)
                .filter(c -> siteId == null || siteId.equals(c.getSiteId()))
                .filter(c -> visible(c, user, sites) || visible(c, user, approvable))
                .map(ContentDto::of).toList();
    }

    @Transactional
    public ContentDto create(ContentInput in, MultipartFile file) {
        requireTargetScope(in.siteId(), in.regionId(), Permission.CONTENT_EDIT);
        validate(in, file != null && !file.isEmpty());
        ContentItem c = new ContentItem();
        apply(c, in);
        c.setCreatedBy(CurrentUser.get().username());
        c.setStatus(ContentItem.Status.DRAFT);
        if (file != null && !file.isEmpty()) {
            MediaStorage.Stored stored = storage.store(file, in.type());
            c.setMediaPath(stored.path());
            c.setMimeType(stored.mimeType());
            c.setSizeBytes(stored.size());
            c.setMediaSha256(stored.sha256());
        }
        repo.save(c);
        audit.record("CONTENT_CREATED", "ContentItem", c.getId(), c.getSiteId(), c.getType() + " " + c.getTitle());
        return ContentDto.of(c);
    }

    @Transactional
    public ContentDto update(UUID id, ContentInput in) {
        ContentItem c = find(id);
        requireLocal(c);
        requireTargetScope(c.getSiteId(), c.getRegionId(), Permission.CONTENT_EDIT);
        requireTargetScope(in.siteId(), in.regionId(), Permission.CONTENT_EDIT);
        if (c.getStatus() != ContentItem.Status.DRAFT && c.getStatus() != ContentItem.Status.REJECTED) {
            throw ApiException.invalidTransition("Only draft or rejected content can be edited");
        }
        if (in.type() != c.getType()) {
            throw ApiException.badRequest("Content type cannot be changed");
        }
        validate(in, c.getMediaPath() != null);
        apply(c, in);
        audit.record("CONTENT_UPDATED", "ContentItem", c.getId(), c.getSiteId(), c.getTitle());
        return ContentDto.of(c);
    }

    @Transactional
    public ContentDto submit(UUID id) {
        ContentItem c = find(id);
        requireLocal(c);
        requireTargetScope(c.getSiteId(), c.getRegionId(), Permission.CONTENT_EDIT);
        transition(c, ContentItem.Status.PENDING_APPROVAL, ContentItem.Status.DRAFT, ContentItem.Status.REJECTED);
        c.setRejectionReason(null);
        audit.record("CONTENT_SUBMITTED", "ContentItem", c.getId(), c.getSiteId(), c.getTitle());
        return ContentDto.of(c);
    }

    @Transactional
    public ContentDto approve(UUID id) {
        ContentItem c = find(id);
        requireLocal(c);
        requireTargetScope(c.getSiteId(), c.getRegionId(), Permission.CONTENT_APPROVE);
        AuthUser user = CurrentUser.get();
        boolean ownContent = user.username().equalsIgnoreCase(c.getCreatedBy());
        if (ownContent && !(superAdminSelfApproval && isSuperAdmin(user))) {
            throw ApiException.forbidden("Content must be approved by someone other than its author");
        }
        transition(c, ContentItem.Status.PUBLISHED, ContentItem.Status.PENDING_APPROVAL);
        c.setApprovedBy(user.username());
        c.setApprovedAt(time.now());
        if (ownContent) {
            // Recorded separately so auditors can review every publication without a second approver.
            audit.record("CONTENT_SELF_APPROVED", "ContentItem", c.getId(), c.getSiteId(), c.getTitle());
        }
        audit.record("CONTENT_PUBLISHED", "ContentItem", c.getId(), c.getSiteId(), c.getTitle());
        return ContentDto.of(c);
    }

    @Transactional
    public ContentDto reject(UUID id, String reason) {
        ContentItem c = find(id);
        requireLocal(c);
        requireTargetScope(c.getSiteId(), c.getRegionId(), Permission.CONTENT_APPROVE);
        transition(c, ContentItem.Status.REJECTED, ContentItem.Status.PENDING_APPROVAL);
        c.setRejectionReason(reason);
        audit.record("CONTENT_REJECTED", "ContentItem", c.getId(), c.getSiteId(), reason);
        return ContentDto.of(c);
    }

    @Transactional
    public ContentDto archive(UUID id) {
        ContentItem c = find(id);
        requireLocal(c);
        requireTargetScope(c.getSiteId(), c.getRegionId(), Permission.CONTENT_EDIT);
        transition(c, ContentItem.Status.ARCHIVED, ContentItem.Status.DRAFT, ContentItem.Status.PENDING_APPROVAL,
                ContentItem.Status.PUBLISHED, ContentItem.Status.REJECTED);
        audit.record("CONTENT_ARCHIVED", "ContentItem", c.getId(), c.getSiteId(), c.getTitle());
        return ContentDto.of(c);
    }

    /** Media of published content only; returns stored file name and mime type. */
    @Transactional(readOnly = true)
    public ContentItem publishedMedia(UUID id) {
        return repo.findById(id).filter(c -> c.getStatus() == ContentItem.Status.PUBLISHED && c.getMediaPath() != null)
                .orElseThrow(() -> ApiException.notFound("Media"));
    }

    private static boolean isSuperAdmin(AuthUser user) {
        return user.grants().stream().anyMatch(g -> "SUPER_ADMIN".equals(g.roleCode()));
    }

    private static void requireLocal(ContentItem c) {
        if (c.getOrigin() == ContentItem.Origin.CENTRAL) {
            throw ApiException.forbidden("This content is managed by the central office and is read-only here");
        }
    }

    private void transition(ContentItem c, ContentItem.Status to, ContentItem.Status... from) {
        for (ContentItem.Status f : from) {
            if (c.getStatus() == f) {
                c.setStatus(to);
                return;
            }
        }
        throw ApiException.invalidTransition("Content in status " + c.getStatus() + " cannot become " + to);
    }

    private void requireTargetScope(UUID siteId, UUID regionId, Permission p) {
        if (siteId != null) {
            scope.requireSite(p, siteId);
        } else if (regionId != null) {
            scope.requireRegion(p, regionId);
        } else {
            scope.requireNational(p);
        }
    }

    private static boolean visible(ContentItem c, AuthUser user, Set<UUID> sites) {
        if (sites == null) {
            return true;
        }
        if (c.getSiteId() != null) {
            return sites.contains(c.getSiteId());
        }
        return c.getRegionId() != null && user.grants().stream().anyMatch(g -> c.getRegionId().equals(g.regionId()));
    }

    private static void validate(ContentInput in, boolean hasMedia) {
        if (in.siteId() != null && in.regionId() != null) {
            throw ApiException.badRequest("Target either a site or a region, not both");
        }
        if (in.startsAt() != null && in.endsAt() != null && !in.endsAt().isAfter(in.startsAt())) {
            throw ApiException.badRequest("End must be after start");
        }
        if (in.type() == ContentItem.Type.ANNOUNCEMENT && (in.body() == null || in.body().isBlank())) {
            throw ApiException.badRequest("Announcements need a body text");
        }
        if (in.type() != ContentItem.Type.ANNOUNCEMENT && !hasMedia) {
            throw ApiException.badRequest("Image and video content need an uploaded file");
        }
    }

    private static void apply(ContentItem c, ContentInput in) {
        c.setTitle(in.title().trim());
        c.setType(in.type());
        c.setBody(in.body());
        c.setSiteId(in.siteId());
        c.setRegionId(in.regionId());
        c.setStartsAt(in.startsAt());
        c.setEndsAt(in.endsAt());
        c.setDisplaySeconds(in.displaySeconds());
    }

    private ContentItem find(UUID id) {
        return repo.findById(id).orElseThrow(() -> ApiException.notFound("Content"));
    }
}
