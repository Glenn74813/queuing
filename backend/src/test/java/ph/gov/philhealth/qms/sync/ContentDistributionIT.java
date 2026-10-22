package ph.gov.philhealth.qms.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import ph.gov.philhealth.qms.cms.MediaStorage;
import ph.gov.philhealth.qms.common.Ids;
import ph.gov.philhealth.qms.security.SitePrincipal;
import ph.gov.philhealth.qms.support.IntegrationTest;
import ph.gov.philhealth.qms.support.TestData;

/** Central → site CMS distribution: targeting, verified media transfer, read-only replicas. */
@IntegrationTest
@TestPropertySource(properties = "qms.node.role=CENTRAL")
class ContentDistributionIT {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8};

    @Autowired
    ConfigSyncService config;
    @Autowired
    MediaStorage media;
    @Autowired
    SiteKeyService siteKeys;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @Autowired
    TestData data;

    private UUID content(UUID siteId, UUID regionId, String status, String mediaPath) {
        UUID id = Ids.newId();
        jdbc.update("""
                insert into content_item (id, title, type, body, media_path, mime_type, size_bytes, site_id, region_id,
                    status, created_by, display_seconds)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'central-admin', 10)
                """, id, "Item " + id, mediaPath != null ? "IMAGE" : "ANNOUNCEMENT", "Text", mediaPath,
                mediaPath != null ? "image/png" : null, mediaPath != null ? (long) PNG.length : null, siteId, regionId,
                status);
        return id;
    }

    private String storePng() throws IOException {
        String name = UUID.randomUUID() + ".png";
        media.storeReplica(name, new ByteArrayInputStream(PNG), PNG.length, null);   // checksum computed later
        return name;
    }

    @Test
    void sitesReceiveOnlyTheirPublishedContentWithVerifiedMedia() throws Exception {
        TestData.SiteFixture site = data.site();
        TestData.SiteFixture otherRegionSite = data.site();
        SitePrincipal principal = new SitePrincipal(site.siteId(), site.siteCode());

        String png = storePng();
        UUID national = content(null, null, "PUBLISHED", png);
        UUID regional = content(null, site.regionId(), "PUBLISHED", null);
        UUID forThisSite = content(site.siteId(), null, "PUBLISHED", null);
        UUID otherRegion = content(null, otherRegionSite.regionId(), "PUBLISHED", null);
        UUID otherSite = content(otherRegionSite.siteId(), null, "PUBLISHED", null);
        UUID draft = content(null, null, "DRAFT", null);

        ConfigBundle bundle = config.bundle(Instant.now().minusSeconds(60), principal);
        List<UUID> ids = bundle.contents().stream().map(ConfigBundle.ContentRow::id).toList();
        assertThat(ids).contains(national, regional, forThisSite).doesNotContain(otherRegion, otherSite, draft);

        // The checksum of pre-existing media is computed for the bundle.
        var row = bundle.contents().stream().filter(c -> c.id().equals(national)).findFirst().orElseThrow();
        assertThat(row.mediaSha256()).hasSize(64).isEqualTo(media.sha256(png));

        // Media download: only for content distributed to the calling site.
        String key = siteKeys.issue(site.siteId());
        byte[] body = mvc.perform(get("/api/v1/sync/media/" + national).header("X-Site-Key", key))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(PNG);
        String otherPng = storePng();
        UUID foreignMedia = content(otherRegionSite.siteId(), null, "PUBLISHED", otherPng);
        mvc.perform(get("/api/v1/sync/media/" + foreignMedia).header("X-Site-Key", key)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/sync/media/" + national)).andExpect(status().isUnauthorized());
    }

    @Test
    void corruptedDownloadsAreRejectedAndLeaveNoFile() throws Exception {
        String name = UUID.randomUUID() + ".png";
        String wrongSha = "0".repeat(64);
        assertThatThrownBy(() -> media.storeReplica(name, new ByteArrayInputStream(PNG), PNG.length, wrongSha))
                .hasMessageContaining("verification");
        assertThatThrownBy(() -> media.storeReplica(name, new ByteArrayInputStream(PNG), PNG.length + 1, null))
                .hasMessageContaining("verification");
        assertThat(media.exists(name)).isFalse();
        assertThatThrownBy(() -> media.storeReplica("../../etc/passwd", new ByteArrayInputStream(PNG), PNG.length, null))
                .hasMessageContaining("Invalid media name");
        try (var files = Files.list(media.resolve(UUID.randomUUID() + ".png").getParent())) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("incoming-"));
        }
    }

    @Test
    void replicatedContentIsAppliedAsReadOnly() throws Exception {
        TestData.SiteFixture site = data.site();
        UUID id = Ids.newId();
        var row = new ConfigBundle.ContentRow(id, "From central", "ANNOUNCEMENT", "National advisory", null, null, null,
                null, null, null, null, null, 15, "PUBLISHED", "central-admin", "central-approver", Instant.now());
        var bundle = new ConfigBundle(Instant.now(), List.of(), List.of(), List.of(), List.of(), List.of(row));
        config.apply(bundle);
        assertThat(jdbc.queryForObject("select origin from content_item where id = ?", String.class, id))
                .isEqualTo("CENTRAL");

        // Local editors cannot change it.
        String editor = data.login(mvc, data.user("CO_ADMIN", null, null));
        mvc.perform(put("/api/v1/content/" + id).header("Authorization", editor).contentType(MediaType.APPLICATION_JSON)
                        .content(data.json(Map.of("title", "Changed", "type", "ANNOUNCEMENT", "body", "x",
                                "displaySeconds", 10))))
                .andExpect(status().isForbidden());

        // Archiving on central removes it from the site; a replica never overwrites local content.
        config.apply(new ConfigBundle(Instant.now(), List.of(), List.of(), List.of(), List.of(), List.of(
                new ConfigBundle.ContentRow(id, "From central", "ANNOUNCEMENT", "National advisory", null, null, null,
                        null, null, null, null, null, 15, "ARCHIVED", "central-admin", "central-approver",
                        Instant.now()))));
        assertThat(jdbc.queryForObject("select status from content_item where id = ?", String.class, id))
                .isEqualTo("ARCHIVED");
        UUID local = content(site.siteId(), null, "PUBLISHED", null);
        config.apply(new ConfigBundle(Instant.now(), List.of(), List.of(), List.of(), List.of(), List.of(
                new ConfigBundle.ContentRow(local, "Hijack", "ANNOUNCEMENT", "x", null, null, null, null, null, null,
                        null, null, 15, "ARCHIVED", "x", "x", Instant.now()))));
        assertThat(jdbc.queryForObject("select status from content_item where id = ?", String.class, local))
                .isEqualTo("PUBLISHED");
    }
}
