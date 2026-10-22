package ph.gov.philhealth.qms.sla;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.audit.AuditService;
import ph.gov.philhealth.qms.catalog.ServiceItem;
import ph.gov.philhealth.qms.catalog.ServiceItemRepository;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.org.Region;
import ph.gov.philhealth.qms.org.RegionRepository;
import ph.gov.philhealth.qms.security.CurrentUser;
import ph.gov.philhealth.qms.security.Permission;
import ph.gov.philhealth.qms.security.ScopeService;

/**
 * Service level targets per region. The national default (region = null) applies to every region
 * without its own policy; per-service overrides refine either.
 */
@Service
public class SlaService {

    public record ServiceTargetDto(UUID serviceItemId, String serviceCode, String serviceName,
                                   Integer waitingTargetMinutes, Integer processingTargetMinutes) {
    }

    /** One row per region (plus the national default), showing the policy in effect. */
    public record SlaPolicyDto(UUID id, UUID regionId, String regionName, boolean inherited,
                               int waitingTargetMinutes, int processingTargetMinutes,
                               BigDecimal complianceTargetPercent, int version, String updatedBy, Instant updatedAt,
                               List<ServiceTargetDto> serviceTargets) {
    }

    public record ServiceTargetInput(@NotNull UUID serviceItemId,
                                     @Min(1) @Max(1440) Integer waitingTargetMinutes,
                                     @Min(1) @Max(1440) Integer processingTargetMinutes) {
    }

    public record SlaPolicyInput(@Min(1) @Max(1440) int waitingTargetMinutes,
                                 @Min(1) @Max(1440) int processingTargetMinutes,
                                 @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal complianceTargetPercent,
                                 @NotNull List<@Valid ServiceTargetInput> serviceTargets) {
    }

    private final SlaPolicyRepository policies;
    private final RegionRepository regions;
    private final ServiceItemRepository items;
    private final ScopeService scope;
    private final AuditService audit;

    public SlaService(SlaPolicyRepository policies, RegionRepository regions, ServiceItemRepository items,
                      ScopeService scope, AuditService audit) {
        this.policies = policies;
        this.regions = regions;
        this.items = items;
        this.scope = scope;
        this.audit = audit;
    }

    /** The national default followed by every region (own policy, or inherited from the default). */
    @Transactional(readOnly = true)
    public List<SlaPolicyDto> list() {
        CurrentUser.get();
        Map<UUID, ServiceItem> catalog = items.findAll().stream()
                .collect(Collectors.toMap(ServiceItem::getId, Function.identity()));
        SlaPolicy national = policies.findNational().orElse(null);
        List<SlaPolicyDto> out = new ArrayList<>();
        if (national != null) {
            out.add(toDto(national, null, "National default", false, catalog));
        }
        for (Region r : regions.findAllByOrderByCodeAsc()) {
            SlaPolicy own = policies.findByRegionId(r.getId()).orElse(null);
            if (own != null) {
                out.add(toDto(own, r.getId(), r.getName(), false, catalog));
            } else if (national != null) {
                out.add(toDto(national, r.getId(), r.getName(), true, catalog));
            }
        }
        return out;
    }

    /** Creates or replaces the policy of a region; {@code regionId == null} edits the national default. */
    @Transactional
    public SlaPolicyDto save(UUID regionId, SlaPolicyInput in) {
        if (regionId == null) {
            scope.requireNational(Permission.SLA_MANAGE);
        } else {
            regions.findById(regionId).orElseThrow(() -> ApiException.notFound("Region"));
            scope.requireRegion(Permission.SLA_MANAGE, regionId);
        }
        SlaPolicy p = (regionId == null ? policies.findNational() : policies.findByRegionId(regionId)).orElse(null);
        String before = p == null ? "inherited" : describe(p);
        if (p == null) {
            p = new SlaPolicy();
            p.setRegionId(regionId);
        } else {
            p.setVersion(p.getVersion() + 1);
        }
        p.setWaitingTargetMinutes(in.waitingTargetMinutes());
        p.setProcessingTargetMinutes(in.processingTargetMinutes());
        p.setComplianceTargetPercent(in.complianceTargetPercent());
        p.setUpdatedBy(CurrentUser.get().username());
        Set<UUID> seen = new HashSet<>();
        p.getServiceTargets().clear();
        for (ServiceTargetInput t : in.serviceTargets()) {
            if (!seen.add(t.serviceItemId())) {
                throw ApiException.badRequest("Each service may have only one override");
            }
            if (t.waitingTargetMinutes() == null && t.processingTargetMinutes() == null) {
                throw ApiException.badRequest("A service override needs a waiting or a processing target");
            }
            items.findById(t.serviceItemId()).orElseThrow(() -> ApiException.badRequest("Unknown service"));
            SlaServiceTarget st = new SlaServiceTarget();
            st.setId(Ids.newId());
            st.setPolicy(p);
            st.setServiceItemId(t.serviceItemId());
            st.setWaitingTargetMinutes(t.waitingTargetMinutes());
            st.setProcessingTargetMinutes(t.processingTargetMinutes());
            p.getServiceTargets().add(st);
        }
        policies.saveAndFlush(p);
        audit.record("SLA_POLICY_SAVED", "SlaPolicy", p.getId(), null,
                (regionId == null ? "national" : "region " + regionId) + " before=" + before + " after=" + describe(p));
        return list().stream().filter(d -> java.util.Objects.equals(d.regionId(), regionId) && !d.inherited())
                .findFirst().orElseThrow();
    }

    /** Removes a region's own policy so it inherits the national default again. */
    @Transactional
    public void removeRegionPolicy(UUID regionId) {
        scope.requireRegion(Permission.SLA_MANAGE, regionId);
        SlaPolicy p = policies.findByRegionId(regionId).orElseThrow(() -> ApiException.notFound("Regional SLA"));
        audit.record("SLA_POLICY_REMOVED", "SlaPolicy", p.getId(), null, "region " + regionId + " was " + describe(p));
        policies.delete(p);
    }

    private SlaPolicyDto toDto(SlaPolicy p, UUID regionId, String regionName, boolean inherited,
                               Map<UUID, ServiceItem> catalog) {
        List<ServiceTargetDto> targets = p.getServiceTargets().stream().map(t -> {
            ServiceItem si = catalog.get(t.getServiceItemId());
            return new ServiceTargetDto(t.getServiceItemId(), si != null ? si.getCode() : null,
                    si != null ? si.getName() : null, t.getWaitingTargetMinutes(), t.getProcessingTargetMinutes());
        }).sorted(java.util.Comparator.comparing(ServiceTargetDto::serviceCode,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))).toList();
        return new SlaPolicyDto(p.getId(), regionId, regionName, inherited, p.getWaitingTargetMinutes(),
                p.getProcessingTargetMinutes(), p.getComplianceTargetPercent(), p.getVersion(), p.getUpdatedBy(),
                p.getUpdatedAt(), targets);
    }

    private static String describe(SlaPolicy p) {
        return "{wait=%d,process=%d,compliance=%s%%,overrides=%s}".formatted(p.getWaitingTargetMinutes(),
                p.getProcessingTargetMinutes(), p.getComplianceTargetPercent(), p.getServiceTargets().stream()
                        .map(t -> t.getServiceItemId() + ":" + t.getWaitingTargetMinutes() + "/"
                                + t.getProcessingTargetMinutes()).toList());
    }
}
