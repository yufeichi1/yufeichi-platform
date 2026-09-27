package com.yufeichi.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yufeichi.server.entity.Tag;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface TagMapper extends BaseMapper<Tag> {
    @Select("SELECT * FROM blog_tag WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Tag selectForUpdate(@Param("id") long id);
}
