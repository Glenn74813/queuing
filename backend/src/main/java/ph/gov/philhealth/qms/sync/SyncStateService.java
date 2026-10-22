package ph.gov.philhealth.qms.sync;

import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncStateService {

    public static final String LAST_PUSH = "last_successful_push_at";
    public static final String LAST_CONFIG_PULL = "last_config_pull_at";
    public static final String CONFIG_WATERMARK = "config_watermark";

    private final SyncStateRepository repo;

    public SyncStateService(SyncStateRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(String name) {
        return repo.findById(name).map(SyncState::getValue);
    }

    @Transactional
    public void put(String name, String value) {
        SyncState s = repo.findById(name).orElseGet(() -> {
            SyncState n = new SyncState();
            n.setName(name);
            return n;
        });
        s.setValue(value);
        s.setUpdatedAt(Instant.now());
        repo.save(s);
    }
}
