package com.yufeichi.server.service;

import com.yufeichi.server.dto.FileUploadRequest;
import com.yufeichi.server.entity.FileInfo;
import com.yufeichi.server.mapper.FileInfoMapper;
import com.yufeichi.server.security.CurrentUser;
import com.yufeichi.server.service.upload.*;
import com.yufeichi.server.vo.FileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.io.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {
    private final FileInfoMapper files;
    private final ImageStorage storage;

    @Transactional(rollbackFor=Exception.class)
    @com.yufeichi.server.security.AuditAction("file.upload")
    public FileVO upload(FileUploadRequest request) throws IOException {
        long uploaderId=CurrentUser.id();
        UploadBizType type=UploadBizType.parse(request.getBizType());
        ImageStorage.DecodedImage image=storage.decode(request.getFile());
        Path dir=storage.directory(type);
        String fileName=UUID.randomUUID()+"."+image.extension();
        Path destination=dir.resolve(fileName);
        Path temporary=Files.createTempFile(dir,".upload-",".tmp");
        AtomicBoolean moved=new AtomicBoolean(false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                cleanup(temporary);
                if(status!=STATUS_COMMITTED && moved.get()) cleanup(destination);
            }
        });
        Files.write(temporary,image.bytes());
        FileInfo file=new FileInfo();
        file.setOriginalName(request.getFile().getOriginalFilename());
        file.setFileName(fileName); file.setFileUrl("/uploads/"+type.directory()+"/"+fileName);
        file.setFilePath(destination.toString()); file.setFileType(image.contentType()); file.setFileExt(image.extension());
        file.setFileSize((long)image.bytes().length); file.setBizType(type.directory()); file.setUploaderId(uploaderId); file.setDeleted(0);
        files.insert(file);
        // The file remains unreadable under its .tmp name until metadata has been written.
        // A later transaction rollback (including commit failure) removes the published file.
        try { Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE); }
        catch(AtomicMoveNotSupportedException e) { Files.move(temporary,destination); }
        moved.set(true);
        return new FileVO(file.getId(),file.getOriginalName(),fileName,file.getFileUrl(),file.getFileType(),
                file.getFileExt(),file.getFileSize(),file.getBizType());
    }
    private void cleanup(Path path) {
        try { Files.deleteIfExists(path); }
        catch(IOException e) { log.error("Failed to compensate uploaded file {}",path,e); }
    }
}
