package ph.gov.philhealth.qms.sla;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ph.gov.philhealth.qms.sla.SlaService.SlaPolicyDto;
import ph.gov.philhealth.qms.sla.SlaService.SlaPolicyInput;

@RestController
public class SlaController {

    private final SlaService service;

    public SlaController(SlaService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/sla-policies")
    @PreAuthorize("hasAnyAuthority('SLA_MANAGE','REPORT_VIEW')")
    public List<SlaPolicyDto> list() {
        return service.list();
    }

    @PutMapping("/api/v1/sla-policies/national")
    @PreAuthorize("hasAuthority('SLA_MANAGE')")
    public SlaPolicyDto saveNational(@Valid @RequestBody SlaPolicyInput in) {
        return service.save(null, in);
    }

    @PutMapping("/api/v1/sla-policies/regions/{regionId}")
    @PreAuthorize("hasAuthority('SLA_MANAGE')")
    public SlaPolicyDto saveRegion(@PathVariable UUID regionId, @Valid @RequestBody SlaPolicyInput in) {
        return service.save(regionId, in);
    }

    @DeleteMapping("/api/v1/sla-policies/regions/{regionId}")
    @PreAuthorize("hasAuthority('SLA_MANAGE')")
    public ResponseEntity<Void> removeRegion(@PathVariable UUID regionId) {
        service.removeRegionPolicy(regionId);
        return ResponseEntity.noContent().build();
    }
}
