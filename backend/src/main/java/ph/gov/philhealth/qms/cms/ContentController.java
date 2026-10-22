package ph.gov.philhealth.qms.cms;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import ph.gov.philhealth.qms.cms.ContentDtos.ContentDto;
import ph.gov.philhealth.qms.cms.ContentDtos.ContentInput;
import ph.gov.philhealth.qms.queue.QueueDtos.ReasonRequest;

@RestController
public class ContentController {

    private final ContentService service;
    private final MediaStorage storage;

    public ContentController(ContentService service, MediaStorage storage) {
        this.service = service;
        this.storage = storage;
    }

    @GetMapping("/api/v1/content")
    @PreAuthorize("hasAnyAuthority('CONTENT_EDIT','CONTENT_APPROVE')")
    public List<ContentDto> list(@RequestParam(required = false) UUID siteId,
                                 @RequestParam(required = false) ContentItem.Status status) {
        return service.list(siteId, status);
    }

    @PostMapping(value = "/api/v1/content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CONTENT_EDIT')")
    public ContentDto create(@Valid @RequestPart("data") ContentInput data,
                             @RequestPart(value = "file", required = false) MultipartFile file) {
        return service.create(data, file);
    }

    @PutMapping("/api/v1/content/{id}")
    @PreAuthorize("hasAuthority('CONTENT_EDIT')")
    public ContentDto update(@PathVariable UUID id, @Valid @RequestBody ContentInput data) {
        return service.update(id, data);
    }

    @PostMapping("/api/v1/content/{id}/submit")
    @PreAuthorize("hasAuthority('CONTENT_EDIT')")
    public ContentDto submit(@PathVariable UUID id) {
        return service.submit(id);
    }

    @PostMapping("/api/v1/content/{id}/approve")
    @PreAuthorize("hasAuthority('CONTENT_APPROVE')")
    public ContentDto approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @PostMapping("/api/v1/content/{id}/reject")
    @PreAuthorize("hasAuthority('CONTENT_APPROVE')")
    public ContentDto reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest req) {
        return service.reject(id, req.reason());
    }

    @PostMapping("/api/v1/content/{id}/archive")
    @PreAuthorize("hasAuthority('CONTENT_EDIT')")
    public ContentDto archive(@PathVariable UUID id) {
        return service.archive(id);
    }

    /** Public so that &lt;img&gt;/&lt;video&gt; tags on displays can load it; only published media is served. */
    @GetMapping("/api/v1/public/media/{id}")
    public ResponseEntity<Resource> media(@PathVariable UUID id) {
        ContentItem c = service.publishedMedia(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.getMimeType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(storage.resolve(c.getMediaPath())));
    }
}
