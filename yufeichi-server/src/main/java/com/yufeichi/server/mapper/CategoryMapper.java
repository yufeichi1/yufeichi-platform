package com.yufeichi.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yufeichi.server.entity.Category;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CategoryMapper extends BaseMapper<Category> {
    @Select("SELECT * FROM blog_category WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Category selectForUpdate(@Param("id") long id);
}
