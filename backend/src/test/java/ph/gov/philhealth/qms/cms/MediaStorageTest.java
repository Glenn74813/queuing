package ph.gov.philhealth.qms.cms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MediaStorageTest {

    @Test
    void detectsByMagicBytesNotName() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] mp4 = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] html = "<html><script>".getBytes();
        byte[] exe = {'M', 'Z', (byte) 0x90, 0};
        assertThat(MediaStorage.detect(png)).isNotNull();
        assertThat(MediaStorage.detect(jpeg)).isNotNull();
        assertThat(MediaStorage.detect(mp4)).isNotNull();
        assertThat(MediaStorage.detect(html)).isNull();
        assertThat(MediaStorage.detect(exe)).isNull();
    }
}
