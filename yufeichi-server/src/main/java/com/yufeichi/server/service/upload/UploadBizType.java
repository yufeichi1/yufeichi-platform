package com.yufeichi.server.service.upload;

import com.yufeichi.server.common.error.*;
import java.util.Locale;

public enum UploadBizType {
    AVATAR, ARTICLE, PROJECT, OTHER;
    public String directory() { return name().toLowerCase(Locale.ROOT); }
    public static UploadBizType parse(String value) {
        for(var type:values()) if(type.directory().equals(value)) return type;
        throw new BusinessException(ErrorCode.PARAM_ERROR,"不支持的图片业务目录");
    }
}
