package ph.gov.philhealth.qms.org;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;
import ph.gov.philhealth.qms.sync.OwnershipGuard;

/** Regional Offices / LHIOs and their geographic grouping. */
@RestController
public class OrgController {

    public record RegionDto(UUID id, String code, String name, boolean active) {
        static RegionDto of(Region r) {
            return new RegionDto(r.getId(), r.getCode(), r.getName(), r.isActive());
        }
    }

    public record RegionInput(@NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Z0-9_-]+$") String code,
                              @NotBlank @Size(max = 150) String name, Boolean active) {
    }

    public record SiteDto(UUID id, String code, String name, Site.Type type, UUID regionId, String regionName,
                          String area, String timezone, boolean active) {
    }

    public record SiteInput(@NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Z0-9_-]+$") String code,
                            @NotBlank @Size(max = 150) String name, @NotNull Site.Type type,
                            @NotNull UUID regionId, @Size(max = 100) String area,
                            @NotBlank String timezone, Boolean active) {
    }

    private final RegionRepository regions;
    private final SiteRepository sites;
    private final ScopeService scope;
    private final AuditService audit;
    private final OwnershipGuard ownership;

    public OrgController(RegionRepository regions, SiteRepository sites, ScopeService scope, AuditService audit,
                         OwnershipGuard ownership) {
        this.regions = regions;
        this.sites = sites;
        this.scope = scope;
        this.audit = audit;
        this.ownership = ownership;
    }

    @GetMapping("/api/v1/regions")
    @Transactional(readOnly = true)
    public List<RegionDto> regions() {
        CurrentUser.get();
        return regions.findAllByOrderByCodeAsc().stream().map(RegionDto::of).toList();
    }

    @PostMapping("/api/v1/regions")
    @PreAuthorize("hasAuthority('REGION_MANAGE')")
    @Transactional
    public RegionDto createRegion(@Valid @RequestBody RegionInput in) {
        scope.requireNational(Permission.REGION_MANAGE);
        ownership.requireCentralOwnedEditable();
        Region r = new Region();
        r.setCode(in.code());
        r.setName(in.name().trim());
        regions.saveAndFlush(r);
        audit.record("REGION_CREATED", "Region", r.getId(), null, r.getCode());
        return RegionDto.of(r);
    }

    @PutMapping("/api/v1/regions/{id}")
    @PreAuthorize("hasAuthority('REGION_MANAGE')")
    @Transactional
    public RegionDto updateRegion(@PathVariable UUID id, @Valid @RequestBody RegionInput in) {
        scope.requireNational(Permission.REGION_MANAGE);
        ownership.requireCentralOwnedEditable();
        Region r = regions.findById(id).orElseThrow(() -> ApiException.notFound("Region"));
        r.setCode(in.code());
        r.setName(in.name().trim());
        if (in.active() != null) {
            r.setActive(in.active());
        }
        audit.record("REGION_UPDATED", "Region", r.getId(), null, r.getCode() + " active=" + r.isActive());
        return RegionDto.of(r);
    }

    /** Sites visible to the caller: everything for national roles, otherwise their region(s)/site(s). */
    @GetMapping("/api/v1/sites")
    @Transactional(readOnly = true)
    public List<SiteDto> sites(@RequestParam(required = false) UUID regionId) {
        var user = CurrentUser.get();
        List<Site> all = regionId != null ? sites.findByRegionIdOrderByCodeAsc(regionId) : sites.findAllByOrderByCodeAsc();
        return all.stream()
                .filter(s -> user.grants().stream().anyMatch(g -> g.covers(s.getId(), s.getRegionId())))
                .map(OrgController::toDto).toList();
    }

    @PostMapping("/api/v1/sites")
    @PreAuthorize("hasAuthority('SITE_MANAGE')")
    @Transactional
    public SiteDto createSite(@Valid @RequestBody SiteInput in) {
        scope.requireRegion(Permission.SITE_MANAGE, in.regionId());
        ownership.requireCentralOwnedEditable();
        Site s = new Site();
        apply(s, in);
        sites.saveAndFlush(s);
        audit.record("SITE_CREATED", "Site", s.getId(), s.getId(), s.getCode() + " " + s.getType());
        return toDto(s);
    }

    @PutMapping("/api/v1/sites/{id}")
    @PreAuthorize("hasAuthority('SITE_MANAGE')")
    @Transactional
    public SiteDto updateSite(@PathVariable UUID id, @Valid @RequestBody SiteInput in) {
        Site s = sites.findById(id).orElseThrow(() -> ApiException.notFound("Site"));
        scope.requireRegion(Permission.SITE_MANAGE, s.getRegionId());
        scope.requireRegion(Permission.SITE_MANAGE, in.regionId());
        ownership.requireCentralOwnedEditable();
        apply(s, in);
        sites.flush();
        audit.record("SITE_UPDATED", "Site", s.getId(), s.getId(), s.getCode() + " active=" + s.isActive());
        return toDto(s);
    }

    private void apply(Site s, SiteInput in) {
        if (!ZoneId.getAvailableZoneIds().contains(in.timezone())) {
            throw ApiException.badRequest("Unknown time zone " + in.timezone());
        }
        s.setCode(in.code());
        s.setName(in.name().trim());
        s.setType(in.type());
        s.setRegion(regions.findById(in.regionId()).orElseThrow(() -> ApiException.badRequest("Unknown region")));
        s.setRegionId(in.regionId());
        s.setArea(in.area());
        s.setTimezone(in.timezone());
        if (in.active() != null) {
            s.setActive(in.active());
        }
    }

    static SiteDto toDto(Site s) {
        return new SiteDto(s.getId(), s.getCode(), s.getName(), s.getType(), s.getRegionId(),
                s.getRegion() != null ? s.getRegion().getName() : null, s.getArea(), s.getTimezone(), s.isActive());
    }
}
