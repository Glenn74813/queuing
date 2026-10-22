package ph.gov.philhealth.qms.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.catalog.CatalogDtos.PriorityCategoryDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.PriorityCategoryInput;
import ph.gov.philhealth.qms.catalog.CatalogDtos.ServiceItemDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.ServiceItemInput;
import ph.gov.philhealth.qms.catalog.CatalogDtos.SiteServiceDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.SiteServiceInput;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;
import ph.gov.philhealth.qms.sync.OutboxService;
import ph.gov.philhealth.qms.sync.OwnershipGuard;
import ph.gov.philhealth.qms.sync.Snapshots;

@Service
public class CatalogService {

    private final ServiceItemRepository items;
    private final SiteServiceRepository siteServices;
    private final PriorityCategoryRepository priorities;
    private final ScopeService scope;
    private final AuditService audit;
    private final OwnershipGuard ownership;
    private final OutboxService outbox;
    private final ServiceItemHistoryService history;

    public CatalogService(ServiceItemRepository items, SiteServiceRepository siteServices,
                          PriorityCategoryRepository priorities, ScopeService scope, AuditService audit,
                          OwnershipGuard ownership, OutboxService outbox, ServiceItemHistoryService history) {
        this.items = items;
        this.siteServices = siteServices;
        this.priorities = priorities;
        this.scope = scope;
        this.audit = audit;
        this.ownership = ownership;
        this.outbox = outbox;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public List<CatalogDtos.ServiceItemVersionDto> itemHistory(UUID id) {
        items.findById(id).orElseThrow(() -> ApiException.notFound("Service item"));
        return history.list(id);
    }

    // ------------------------------------------------------------------ national catalog

    @Transactional(readOnly = true)
    public List<ServiceItemDto> listItems() {
        return items.findAllByOrderBySortOrderAscCodeAsc().stream().map(ServiceItemDto::of).toList();
    }

    @Transactional
    public ServiceItemDto createItem(ServiceItemInput in) {
        scope.requireNational(Permission.CATALOG_MANAGE);
        ownership.requireCentralOwnedEditable();
        ServiceItem s = new ServiceItem();
        apply(s, in);
        items.saveAndFlush(s);
        history.record(s);
        audit.record("CATALOG_ITEM_CREATED", "ServiceItem", s.getId(), null, describe(s));
        return ServiceItemDto.of(s);
    }

    @Transactional
    public ServiceItemDto updateItem(UUID id, ServiceItemInput in) {
        scope.requireNational(Permission.CATALOG_MANAGE);
        ownership.requireCentralOwnedEditable();
        ServiceItem s = items.findById(id).orElseThrow(() -> ApiException.notFound("Service item"));
        String before = describe(s);
        apply(s, in);
        s.setVersion(s.getVersion() + 1);
        items.flush();
        history.record(s);
        audit.record("CATALOG_ITEM_UPDATED", "ServiceItem", s.getId(), null,
                "before=" + before + " after=" + describe(s));
        return ServiceItemDto.of(s);
    }

    private static void apply(ServiceItem s, ServiceItemInput in) {
        s.setCode(in.code());
        s.setName(in.name().trim());
        s.setDescription(in.description());
        s.setClassification(in.classification());
        s.setClientType(in.clientType());
        s.setServiceType(in.serviceType());
        s.setMultiStage(in.multiStage());
        s.setDefaultPrefix(in.defaultPrefix());
        s.setDefaultDurationMinutes(in.defaultDurationMinutes());
        s.setRequiredDocuments(in.requiredDocuments());
        s.setEligibilityNotes(in.eligibilityNotes());
        s.setActive(in.active());
        s.setSortOrder(in.sortOrder());
        if (in.effectiveFrom() != null && in.effectiveTo() != null && !in.effectiveTo().isAfter(in.effectiveFrom())) {
            throw ApiException.badRequest("Effective-to date must be after the effective-from date");
        }
        s.setEffectiveFrom(in.effectiveFrom());
        s.setEffectiveTo(in.effectiveTo());
    }

    private static String describe(ServiceItem s) {
        return "{code=%s,name=%s,class=%s,client=%s,type=%s,multiStage=%s,active=%s,from=%s,to=%s,v=%d}".formatted(
                s.getCode(), s.getName(), s.getClassification(), s.getClientType(), s.getServiceType(),
                s.isMultiStage(), s.isActive(), s.getEffectiveFrom(), s.getEffectiveTo(), s.getVersion());
    }

    // ------------------------------------------------------------------ site services

    @Transactional(readOnly = true)
    public List<SiteServiceDto> listSiteServices(UUID siteId) {
        scope.requireSite(Permission.QUEUE_VIEW, siteId);
        return siteServices.findBySiteIdOrderBySortOrderAsc(siteId).stream().map(SiteServiceDto::of).toList();
    }

    @Transactional
    public SiteServiceDto createSiteService(UUID siteId, SiteServiceInput in) {
        scope.requireSite(Permission.SITE_SERVICE_MANAGE, siteId);
        SiteService s = new SiteService();
        s.setSiteId(siteId);
        apply(s, in);
        siteServices.saveAndFlush(s);
        audit.record("SITE_SERVICE_CREATED", "SiteService", s.getId(), siteId, s.getPrefix() + " " + in.serviceItemId());
        outbox.enqueue("SITE_SERVICE_UPSERT", "SiteService", s.getId().toString(), Snapshots.of(s));
        return SiteServiceDto.of(s);
    }

    @Transactional
    public SiteServiceDto updateSiteService(UUID siteId, UUID id, SiteServiceInput in) {
        scope.requireSite(Permission.SITE_SERVICE_MANAGE, siteId);
        SiteService s = siteServices.findById(id).filter(x -> x.getSiteId().equals(siteId))
                .orElseThrow(() -> ApiException.notFound("Site service"));
        if (!s.getServiceItem().getId().equals(in.serviceItemId())) {
            throw ApiException.badRequest("The catalog item of a site service cannot be changed");
        }
        apply(s, in);
        siteServices.flush();
        audit.record("SITE_SERVICE_UPDATED", "SiteService", s.getId(), siteId,
                "prefix=%s active=%s order=%d".formatted(s.getPrefix(), s.isActive(), s.getSortOrder()));
        outbox.enqueue("SITE_SERVICE_UPSERT", "SiteService", s.getId().toString(), Snapshots.of(s));
        return SiteServiceDto.of(s);
    }

    private void apply(SiteService s, SiteServiceInput in) {
        if (in.openTime() != null && in.closeTime() != null && !in.closeTime().isAfter(in.openTime())) {
            throw ApiException.badRequest("Closing time must be after opening time");
        }
        s.setServiceItem(items.findById(in.serviceItemId()).orElseThrow(() -> ApiException.notFound("Service item")));
        s.setPrefix(in.prefix());
        s.setActive(in.active());
        s.setSortOrder(in.sortOrder());
        s.setEstimatedDurationMinutes(in.estimatedDurationMinutes());
        s.setOpenTime(in.openTime());
        s.setCloseTime(in.closeTime());
        s.setNextSiteServiceId(validNextStage(s, in.nextSiteServiceId()));
    }

    /** The next stage must be another service of the same site, and the chain must not loop back. */
    private UUID validNextStage(SiteService s, UUID nextId) {
        if (nextId == null) {
            return null;
        }
        if (nextId.equals(s.getId())) {
            throw ApiException.badRequest("A service cannot be its own next stage");
        }
        SiteService next = siteServices.findById(nextId).filter(n -> n.getSiteId().equals(s.getSiteId()))
                .orElseThrow(() -> ApiException.badRequest("The next stage must be a service of the same site"));
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        if (s.getId() != null) {
            seen.add(s.getId());
        }
        for (SiteService cur = next; cur != null; ) {
            if (!seen.add(cur.getId())) {
                throw ApiException.badRequest("This next stage would create a loop: " + cur.getServiceItem().getName()
                        + " already leads back here");
            }
            UUID following = cur.getNextSiteServiceId();
            cur = following == null ? null : siteServices.findById(following).orElse(null);
        }
        return nextId;
    }

    // ------------------------------------------------------------------ priority categories

    @Transactional(readOnly = true)
    public List<PriorityCategoryDto> listPriorities() {
        return priorities.findAllByOrderByRankAscCodeAsc().stream().map(PriorityCategoryDto::of).toList();
    }

    @Transactional
    public PriorityCategoryDto createPriority(PriorityCategoryInput in) {
        scope.requireNational(Permission.PRIORITY_MANAGE);
        ownership.requireCentralOwnedEditable();
        PriorityCategory p = new PriorityCategory();
        apply(p, in);
        priorities.save(p);
        audit.record("PRIORITY_CREATED", "PriorityCategory", p.getId(), null, in.toString());
        return PriorityCategoryDto.of(p);
    }

    @Transactional
    public PriorityCategoryDto updatePriority(UUID id, PriorityCategoryInput in) {
        scope.requireNational(Permission.PRIORITY_MANAGE);
        ownership.requireCentralOwnedEditable();
        PriorityCategory p = priorities.findById(id).orElseThrow(() -> ApiException.notFound("Priority category"));
        String before = PriorityCategoryDto.of(p).toString();
        apply(p, in);
        audit.record("PRIORITY_UPDATED", "PriorityCategory", p.getId(), null, "before=" + before + " after=" + in);
        return PriorityCategoryDto.of(p);
    }

    private static void apply(PriorityCategory p, PriorityCategoryInput in) {
        p.setCode(in.code());
        p.setName(in.name().trim());
        p.setDescription(in.description());
        p.setRank(in.rank());
        p.setActive(in.active());
    }
}
