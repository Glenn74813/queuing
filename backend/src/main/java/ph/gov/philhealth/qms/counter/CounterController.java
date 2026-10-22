package ph.gov.philhealth.qms.counter;

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
import ph.gov.philhealth.qms.counter.CounterDtos.CounterDto;
import ph.gov.philhealth.qms.counter.CounterDtos.CounterInput;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceDto;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceInput;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceUpdate;
import ph.gov.philhealth.qms.counter.CounterDtos.DeviceWithKey;

@RestController
@RequestMapping("/api/v1/sites/{siteId}")
public class CounterController {

    private final CounterService counters;
    private final DeviceService devices;

    public CounterController(CounterService counters, DeviceService devices) {
        this.counters = counters;
        this.devices = devices;
    }

    @GetMapping("/counters")
    public List<CounterDto> counters(@PathVariable UUID siteId) {
        return counters.list(siteId);
    }

    @PostMapping("/counters")
    @PreAuthorize("hasAuthority('COUNTER_MANAGE')")
    public CounterDto createCounter(@PathVariable UUID siteId, @Valid @RequestBody CounterInput in) {
        return counters.create(siteId, in);
    }

    @PutMapping("/counters/{id}")
    @PreAuthorize("hasAuthority('COUNTER_MANAGE')")
    public CounterDto updateCounter(@PathVariable UUID siteId, @PathVariable UUID id,
                                    @Valid @RequestBody CounterInput in) {
        return counters.update(siteId, id, in);
    }

    @GetMapping("/devices")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    public List<DeviceDto> devices(@PathVariable UUID siteId) {
        return devices.list(siteId);
    }

    @PostMapping("/devices")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    public DeviceWithKey createDevice(@PathVariable UUID siteId, @Valid @RequestBody DeviceInput in) {
        return devices.create(siteId, in);
    }

    @PutMapping("/devices/{id}")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    public DeviceDto updateDevice(@PathVariable UUID siteId, @PathVariable UUID id,
                                  @Valid @RequestBody DeviceUpdate in) {
        return devices.update(siteId, id, in);
    }

    @PostMapping("/devices/{id}/rotate-key")
    @PreAuthorize("hasAuthority('DEVICE_MANAGE')")
    public DeviceWithKey rotateKey(@PathVariable UUID siteId, @PathVariable UUID id) {
        return devices.rotateKey(siteId, id);
    }
}
