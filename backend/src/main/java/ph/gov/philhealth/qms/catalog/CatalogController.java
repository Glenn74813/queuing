package ph.gov.philhealth.qms.catalog;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.catalog.CatalogDtos.AddCatalogServicesRequest;
import ph.gov.philhealth.qms.catalog.CatalogDtos.PriorityCategoryDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.PriorityCategoryInput;
import ph.gov.philhealth.qms.catalog.CatalogDtos.ServiceItemDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.ServiceItemInput;
import ph.gov.philhealth.qms.catalog.CatalogDtos.SiteServiceDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.SiteServiceInput;

@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/service-items")
    public List<ServiceItemDto> items() {
        return service.listItems();
    }

    @PostMapping("/service-items")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public ServiceItemDto createItem(@Valid @RequestBody ServiceItemInput in) {
        return service.createItem(in);
    }

    @PutMapping("/service-items/{id}")
    @PreAuthorize("hasAuthority('CATALOG_MANAGE')")
    public ServiceItemDto updateItem(@PathVariable UUID id, @Valid @RequestBody ServiceItemInput in) {
        return service.updateItem(id, in);
    }

    @GetMapping("/service-items/{id}/history")
    public List<CatalogDtos.ServiceItemVersionDto> itemHistory(@PathVariable UUID id) {
        return service.itemHistory(id);
    }

    @GetMapping("/sites/{siteId}/services")
    public List<SiteServiceDto> siteServices(@PathVariable UUID siteId) {
        return service.listSiteServices(siteId);
    }

    @PostMapping("/sites/{siteId}/services")
    @PreAuthorize("hasAuthority('SITE_SERVICE_MANAGE')")
    public SiteServiceDto createSiteService(@PathVariable UUID siteId, @Valid @RequestBody SiteServiceInput in) {
        return service.createSiteService(siteId, in);
    }

    /** Adds catalog services the site does not offer yet: all active ones, or those listed. */
    @PostMapping("/sites/{siteId}/services/add-catalog")
    @PreAuthorize("hasAuthority('SITE_SERVICE_MANAGE')")
    public List<SiteServiceDto> addCatalogServices(@PathVariable UUID siteId,
                                                   @RequestBody(required = false) AddCatalogServicesRequest in) {
        return service.addCatalogServices(siteId, in != null ? in.serviceItemIds() : null);
    }

    @PutMapping("/sites/{siteId}/services/{id}")
    @PreAuthorize("hasAuthority('SITE_SERVICE_MANAGE')")
    public SiteServiceDto updateSiteService(@PathVariable UUID siteId, @PathVariable UUID id,
                                            @Valid @RequestBody SiteServiceInput in) {
        return service.updateSiteService(siteId, id, in);
    }

    @GetMapping("/priority-categories")
    public List<PriorityCategoryDto> priorities() {
        return service.listPriorities();
    }

    @PostMapping("/priority-categories")
    @PreAuthorize("hasAuthority('PRIORITY_MANAGE')")
    public PriorityCategoryDto createPriority(@Valid @RequestBody PriorityCategoryInput in) {
        return service.createPriority(in);
    }

    @PutMapping("/priority-categories/{id}")
    @PreAuthorize("hasAuthority('PRIORITY_MANAGE')")
    public PriorityCategoryDto updatePriority(@PathVariable UUID id, @Valid @RequestBody PriorityCategoryInput in) {
        return service.updatePriority(id, in);
    }
}
