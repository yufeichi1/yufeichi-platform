package com.yufeichi.server.config;

import com.yufeichi.server.service.upload.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.*;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.resource.PathResourceResolver;
import java.io.IOException;
import java.nio.file.Path;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {
    private final ImageStorage storage;
    @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**").addResourceLocations(storage.root().toUri().toString())
                .setCacheControl(CacheControl.noCache()).resourceChain(false)
                .addResolver(new PathResourceResolver() {
                    @Override protected Resource getResource(String path,Resource location) throws IOException {
                        Path file=storage.publicFile(path);
                        return file==null ? null : new FileSystemResource(file);
                    }
                });
    }
}
