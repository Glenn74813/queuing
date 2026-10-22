package ph.gov.philhealth.qms.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.common.Hashing;
import ph.gov.philhealth.qms.common.Ids;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Creates isolated organizations, users and devices for each test (unique codes, shared database). */
@TestComponent
public class TestData {

    public static final String PASSWORD = "Correct-Horse-42";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ObjectMapper mapper;
    private final String passwordHash;

    public TestData(JdbcTemplate jdbc, PasswordEncoder encoder, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mapper = mapper;
        this.passwordHash = encoder.encode(PASSWORD);
    }

    public record SiteFixture(UUID regionId, UUID siteId, String siteCode, UUID serviceA, UUID serviceB,
                              UUID counter1, UUID counter2, String kioskKey, UUID kioskId, String displayKey) {
    }

    public static String unique(String prefix) {
        return prefix + Long.toString(Math.abs(UUID.randomUUID().getMostSignificantBits()), 36).substring(0, 6).toUpperCase();
    }

    /** A region with one LHIO, two services (SVC-01 "A", SVC-21 "W"), two counters serving both, a kiosk and a display. */
    public SiteFixture site() {
        UUID region = Ids.newId();
        jdbc.update("insert into region (id, code, name) values (?, ?, ?)", region, unique("R"), "Test Region");
        UUID site = Ids.newId();
        String siteCode = unique("S");
        jdbc.update("insert into site (id, code, name, type, region_id, area, timezone) values (?,?,?,?,?,?,?)",
                site, siteCode, "LHIO " + siteCode, "LHIO", region, "Area 1", "Asia/Manila");
        UUID a = siteService(site, "SVC-01", "A");
        UUID b = siteService(site, "SVC-21", "W");
        UUID c1 = counter(site, "C1", List.of(a, b));
        UUID c2 = counter(site, "C2", List.of(a, b));
        String kioskKey = "kiosk-" + UUID.randomUUID();
        UUID kioskId = device(site, "KIOSK", kioskKey);
        String displayKey = "display-" + UUID.randomUUID();
        device(site, "DISPLAY", displayKey);
        return new SiteFixture(region, site, siteCode, a, b, c1, c2, kioskKey, kioskId, displayKey);
    }

    private UUID siteService(UUID site, String itemCode, String prefix) {
        UUID id = Ids.newId();
        UUID item = jdbc.queryForObject("select id from service_item where code = ?", UUID.class, itemCode);
        jdbc.update("""
                insert into site_service (id, site_id, service_item_id, prefix, estimated_duration_minutes, sort_order)
                values (?,?,?,?,10,1)""", id, site, item, prefix);
        return id;
    }

    private UUID counter(UUID site, String code, List<UUID> services) {
        UUID id = Ids.newId();
        jdbc.update("insert into service_counter (id, site_id, code, name) values (?,?,?,?)", id, site, code,
                "Counter " + code.substring(1));
        services.forEach(s -> jdbc.update("insert into counter_service values (?, ?)", id, s));
        return id;
    }

    private UUID device(UUID site, String type, String key) {
        UUID id = Ids.newId();
        jdbc.update("insert into device (id, site_id, code, name, type, api_key_hash) values (?,?,?,?,?,?)",
                id, site, type + "-" + id.toString().substring(0, 6), type, type, Hashing.sha256Hex(key));
        return id;
    }

    /** Creates an active user with one role assignment; scope target may be null (national). */
    public String user(String role, UUID regionId, UUID siteId) {
        String username = unique("u").toLowerCase();
        UUID id = Ids.newId();
        jdbc.update("""
                insert into app_user (id, username, full_name, password_hash, status, must_change_password)
                values (?,?,?,?, 'ACTIVE', false)""", id, username, "User " + username, passwordHash);
        jdbc.update("insert into user_role_assignment (id, user_id, role_code, region_id, site_id) values (?,?,?,?,?)",
                Ids.newId(), id, role, regionId, siteId);
        return username;
    }

    public String login(MockMvc mvc, String username) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = mapper.readTree(body);
        if (!json.has("accessToken")) {
            throw new IllegalStateException("Login failed: " + body);
        }
        return "Bearer " + json.get("accessToken").asText();
    }

    public String json(Object o) throws Exception {
        return mapper.writeValueAsString(o);
    }

    public JsonNode read(String s) throws Exception {
        return mapper.readTree(s);
    }
}
