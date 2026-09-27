package com.yufeichi.server.service;

import com.yufeichi.server.common.error.BusinessException;
import com.yufeichi.server.service.upload.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32;
import static org.assertj.core.api.Assertions.*;

class ImageStorageTest {
    @TempDir Path root;
    @Test void rejectsExcessiveDimensionsBeforeAllocatingImage() throws Exception {
        var storage=new ImageStorage(root.toString());
        assertThatThrownBy(()->storage.decode(file("image.png",pngHeader(8193,1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尺寸");
        assertThatThrownBy(()->storage.decode(file("image.png",pngHeader(4001,4000))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("尺寸");
    }
    @ParameterizedTest
    @ValueSource(strings={"../image.png","..\\image.png","C:image.png","image.html","image.svg","image.php","image.png.exe"})
    void rejectsUnsafeNamesAndExtensions(String name) throws Exception {
        var storage=new ImageStorage(root.toString());
        assertThatThrownBy(()->storage.decode(file(name,pngHeader(1,1)))).isInstanceOf(BusinessException.class);
    }
    @ParameterizedTest
    @ValueSource(strings={"../application.yml","article/../../application.yml","article/.upload-secret.tmp","article/image.svg","C:/private.png","other/%2e%2e/private.png"})
    void publicResolverDoesNotServeUntrustedPaths(String path) throws Exception {
        assertThat(new ImageStorage(root.toString()).publicFile(path)).isNull();
    }
    @Test void stripsTrailingPayloadAndIgnoresClaimedMimeType() throws Exception {
        byte[] valid=pngHeader(1,1);
        var out=new ByteArrayOutputStream(); out.write(valid); out.write("<script>active-payload</script>".getBytes());
        var decoded=new ImageStorage(root.toString()).decode(new MockMultipartFile("file","image.png","text/html",out.toByteArray()));
        assertThat(decoded.contentType()).isEqualTo("image/png");
        assertThat(new String(decoded.bytes(),java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("active-payload");
        assertThat(ImageIO.read(new ByteArrayInputStream(decoded.bytes())).getWidth()).isEqualTo(1);
    }
    @Test void invalidBusinessDirectoryCannotEscapeRoot() {
        assertThatThrownBy(()->UploadBizType.parse("../outside")).isInstanceOf(BusinessException.class);
    }
    private MockMultipartFile file(String name,byte[] bytes) { return new MockMultipartFile("file",name,"image/png",bytes); }
    private byte[] pngHeader(int width,int height) throws Exception {
        var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",out);
        byte[] data=out.toByteArray(); ByteBuffer.wrap(data,16,8).putInt(width).putInt(height);
        var crc=new CRC32(); crc.update(data,12,17); ByteBuffer.wrap(data,29,4).putInt((int)crc.getValue());
        return data;
    }
}
