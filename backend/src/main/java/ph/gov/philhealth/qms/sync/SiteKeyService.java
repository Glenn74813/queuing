package ph.gov.philhealth.qms.sync;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.security.SitePrincipal;

/** Keys that site nodes use to authenticate to the central node. Only SHA-256 hashes are stored. */
@Service
public class SiteKeyService {

    private final JdbcTemplate jdbc;

    public SiteKeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Optional<SitePrincipal> authenticate(String keyHash) {
        return jdbc.query("""
                        select s.id, s.code from sync_site_key k join site s on s.id = k.site_id
                        where k.key_hash = ? and s.active
                        """,
                (rs, i) -> new SitePrincipal(rs.getObject(1, UUID.class), rs.getString(2)), keyHash)
                .stream().findFirst();
    }

    /** Creates or replaces the key of a site and returns the raw key (shown once). */
    @Transactional
    public String issue(UUID siteId) {
        String raw = "qsk_" + Hashing.randomToken(32);
        jdbc.update("""
                insert into sync_site_key (site_id, key_hash, created_at) values (?, ?, now())
                on conflict (site_id) do update set key_hash = excluded.key_hash, created_at = now()
                """, siteId, Hashing.sha256Hex(raw));
        return raw;
    }
}
