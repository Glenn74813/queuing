package ph.gov.philhealth.qms.cms;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import ph.gov.philhealth.qms.common.ApiException;
import ph.gov.philhealth.qms.config.QmsProperties;

/**
 * Stores CMS media on the local file system under a server-generated name. The file type is
 * determined from the file's magic bytes, never from the client-supplied name or content type.
 */
@Component
public class MediaStorage {

    public record Stored(String path, String mimeType, long size, String sha256) {
    }

    private record Kind(String mime, String ext, long maxBytes, ContentItem.Type type) {
    }

    private static final long MB = 1024L * 1024L;
    private static final Map<String, Kind> KINDS = Map.of(
            "png", new Kind("image/png", "png", 10 * MB, ContentItem.Type.IMAGE),
            "jpeg", new Kind("image/jpeg", "jpg", 10 * MB, ContentItem.Type.IMAGE),
            "webp", new Kind("image/webp", "webp", 10 * MB, ContentItem.Type.IMAGE),
            "mp4", new Kind("video/mp4", "mp4", 200 * MB, ContentItem.Type.VIDEO),
            "webm", new Kind("video/webm", "webm", 200 * MB, ContentItem.Type.VIDEO));

    private final Path root;

    public MediaStorage(QmsProperties props) throws IOException {
        this.root = Path.of(props.storage().mediaDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public Stored store(MultipartFile file, ContentItem.Type expected) {
        if (file.isEmpty()) {
            throw ApiException.badRequest("Uploaded file is empty");
        }
        byte[] head = new byte[16];
        int read;
        try (InputStream in = file.getInputStream()) {
            read = in.readNBytes(head, 0, head.length);
        } catch (IOException e) {
            throw ApiException.badRequest("Unable to read upload");
        }
        Kind kind = detect(Arrays.copyOf(head, read));
        if (kind == null) {
            throw ApiException.badRequest("File type not allowed. Use PNG, JPEG, WebP, MP4 or WebM.");
        }
        if (kind.type() != expected) {
            throw ApiException.badRequest("File type does not match content type " + expected);
        }
        if (file.getSize() > kind.maxBytes()) {
            throw ApiException.badRequest("File exceeds the " + kind.maxBytes() / MB + " MB limit");
        }
        String name = UUID.randomUUID() + "." + kind.ext();
        try (DigestInputStream in = new DigestInputStream(file.getInputStream(), sha256Digest())) {
            Files.copy(in, root.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            return new Stored(name, kind.mime(), file.getSize(), HexFormat.of().formatHex(in.getMessageDigest().digest()));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store media", e);
        }
    }

    /** Stored names are always server-generated: a UUID plus a known extension. */
    public static boolean isValidStoredName(String name) {
        return name != null && name.matches("^[0-9a-f-]{36}\\.(png|jpg|webp|mp4|webm)$");
    }

    public boolean exists(String storedName) {
        return isValidStoredName(storedName) && Files.isRegularFile(resolve(storedName));
    }

    public String sha256(String storedName) {
        try (InputStream in = new DigestInputStream(Files.newInputStream(resolve(storedName)), sha256Digest())) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read media " + storedName, e);
        }
    }

    /**
     * Saves media replicated from central: streams to a temporary file, verifies size and SHA-256,
     * then moves it into place, so a partial or corrupted download is never served.
     */
    public void storeReplica(String storedName, InputStream body, long expectedSize, String expectedSha256)
            throws IOException {
        if (!isValidStoredName(storedName)) {
            throw new IOException("Invalid media name " + storedName);
        }
        Path tmp = Files.createTempFile(root, "incoming-", ".part");
        try {
            String sha;
            try (DigestInputStream in = new DigestInputStream(body, sha256Digest())) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                sha = HexFormat.of().formatHex(in.getMessageDigest().digest());
            }
            if (Files.size(tmp) != expectedSize || (expectedSha256 != null && !expectedSha256.equals(sha))) {
                throw new IOException("Media " + storedName + " failed size/SHA-256 verification");
            }
            Files.move(tmp, resolve(storedName), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path resolve(String storedName) {
        Path p = root.resolve(storedName).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.notFound("Media");
        }
        return p;
    }

    static Kind detect(byte[] b) {
        if (startsWith(b, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return KINDS.get("png");
        }
        if (startsWith(b, 0, 0xFF, 0xD8, 0xFF)) {
            return KINDS.get("jpeg");
        }
        if (startsWith(b, 0, 'R', 'I', 'F', 'F') && startsWith(b, 8, 'W', 'E', 'B', 'P')) {
            return KINDS.get("webp");
        }
        if (startsWith(b, 4, 'f', 't', 'y', 'p')) {
            return KINDS.get("mp4");
        }
        if (startsWith(b, 0, 0x1A, 0x45, 0xDF, 0xA3)) {
            return KINDS.get("webm");
        }
        return null;
    }

    private static boolean startsWith(byte[] data, int offset, int... sig) {
        if (data.length < offset + sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((data[offset + i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
