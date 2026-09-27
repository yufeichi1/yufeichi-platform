package com.yufeichi.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yufeichi.server.entity.Project;
import org.apache.ibatis.annotations.*;

public interface ProjectMapper extends BaseMapper<Project> {
    @Select("SELECT * FROM project WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Project selectForUpdate(@Param("id") long id);
}
