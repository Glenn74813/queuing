package ph.gov.philhealth.qms.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ph.gov.philhealth.qms.catalog.CatalogDtos.ServiceItemDto;
import ph.gov.philhealth.qms.catalog.CatalogDtos.ServiceItemVersionDto;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.security.CurrentUser;

/** Keeps a full snapshot of every catalog item version, so any past configuration can be reproduced. */
@Service
public class ServiceItemHistoryService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ServiceItemHistoryService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(ServiceItem item) {
        String snapshot;
        try {
            snapshot = mapper.writeValueAsString(ServiceItemDto.of(item));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.update("""
                insert into service_item_history (id, service_item_id, version, snapshot, changed_by, changed_at)
                values (?, ?, ?, ?, ?, ?)
                """, Ids.newId(), item.getId(), item.getVersion(), snapshot,
                CurrentUser.find().map(u -> u.username()).orElse(null), Timestamp.from(Instant.now()));
    }

    @Transactional(readOnly = true)
    public List<ServiceItemVersionDto> list(UUID itemId) {
        return jdbc.query("""
                select version, snapshot, changed_by, changed_at from service_item_history
                where service_item_id = ? order by version desc
                """, (rs, i) -> new ServiceItemVersionDto(rs.getInt(1), rs.getString(2), rs.getString(3),
                rs.getTimestamp(4).toInstant()), itemId);
    }
}
