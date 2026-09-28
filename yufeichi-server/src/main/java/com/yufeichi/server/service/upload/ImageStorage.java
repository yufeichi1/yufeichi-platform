package com.yufeichi.server.service.upload;

import com.yufeichi.server.common.error.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;

@Component
public class ImageStorage {
    public static final long MAX_BYTES=5L*1024*1024;
    public static final long MAX_PIXELS=16_000_000L;
    public static final int MAX_DIMENSION=8192;
    private final Path root;

    public ImageStorage(@Value("${file.upload-path:./uploads}") String uploadPath) throws IOException {
        Path configured=Path.of(uploadPath).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        root=configured.toRealPath();
    }
    public Path root() { return root; }

    public DecodedImage decode(MultipartFile file) {
        if(file==null || file.isEmpty()) throw invalid("图片不能为空");
        if(file.getSize()>MAX_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        String name=file.getOriginalFilename();
        if(name==null || name.isBlank() || name.length()>255 || name.contains("/") || name.contains("\\")
                || name.contains(":") || name.chars().anyMatch(Character::isISOControl)) throw invalid("文件名不合法");
        int dot=name.lastIndexOf('.');
        String ext=dot<0 ? "" : name.substring(dot+1).toLowerCase(Locale.ROOT);
        String expected=switch(ext) { case "jpg","jpeg" -> "jpeg"; case "png" -> "png"; case "webp" -> "webp"; default -> throw invalid("仅支持JPEG、PNG和WebP图片"); };
        String declared=file.getContentType();
        if(declared!=null && !declared.isBlank() && !declared.equalsIgnoreCase("application/octet-stream")
                && !declared.equalsIgnoreCase("image/"+expected))
            throw invalid("图片MIME与扩展名不一致");
        BufferedImage decoded;
        try(InputStream input=file.getInputStream()) {
            byte[] bytes=input.readNBytes((int)MAX_BYTES+1);
            if(bytes.length>MAX_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
            try(var imageInput=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                Iterator<ImageReader> readers=ImageIO.getImageReaders(imageInput);
                if(!readers.hasNext()) throw invalid("无法解码图片");
                ImageReader reader=readers.next();
                try {
                    if(!reader.getFormatName().equalsIgnoreCase(expected)) throw invalid("图片扩展名与实际格式不一致");
                    reader.setInput(imageInput,true,true);
                    int width=reader.getWidth(0),height=reader.getHeight(0);
                    if(width<=0 || height<=0 || width>MAX_DIMENSION || height>MAX_DIMENSION || (long)width*height>MAX_PIXELS)
                        throw invalid("图片尺寸超过限制");
                    decoded=reader.read(0);
                    if(decoded==null) throw invalid("无法解码图片");
                } finally { reader.dispose(); }
            }
        } catch(BusinessException e) { throw e; }
        catch(IOException | RuntimeException e) { throw invalid("图片损坏或格式不受支持"); }
        // Re-encode pixels, discarding uploaded metadata, trailing bytes and active payloads.
        String output=expected.equals("jpeg") ? "jpg" : "png";
        BufferedImage clean=new BufferedImage(decoded.getWidth(),decoded.getHeight(),
                output.equals("jpg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        var graphics=clean.createGraphics();
        try { graphics.drawImage(decoded,0,0,null); } finally { graphics.dispose(); decoded.flush(); }
        try(var buffer=new ByteArrayOutputStream()) {
            if(!ImageIO.write(clean,output,buffer)) throw new BusinessException(ErrorCode.FILE_UPLOAD_ERROR);
            if(buffer.size()>MAX_BYTES) throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE,"规范化后的图片超过5MB");
            return new DecodedImage(buffer.toByteArray(),output,output.equals("jpg") ? "image/jpeg" : "image/png");
        } catch(IOException e) { throw new UncheckedIOException("Image encoding failed",e); }
        finally { clean.flush(); }
    }

    public Path directory(UploadBizType type) throws IOException {
        Path dir=root.resolve(type.directory());
        if(Files.isSymbolicLink(dir)) throw new IOException("Upload directory must not be a symbolic link");
        Files.createDirectories(dir);
        if(!dir.toRealPath().startsWith(root)) throw new IOException("Upload directory outside configured root");
        return dir;
    }

    public Path publicFile(String path) throws IOException {
        if(!path.matches("(?:avatar|article|project|other)/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.(?:png|jpg)")) return null;
        Path candidate=root.resolve(path).normalize();
        if(!candidate.startsWith(root) || Files.isSymbolicLink(candidate.getParent()) || Files.isSymbolicLink(candidate)
                || !Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)) return null;
        return candidate.toRealPath().startsWith(root) ? candidate : null;
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.PARAM_ERROR,message); }
    public record DecodedImage(byte[] bytes,String extension,String contentType) {}
}
