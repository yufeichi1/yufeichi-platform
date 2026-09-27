package com.yufeichi.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yufeichi.server.entity.Article;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ArticleMapper extends BaseMapper<Article> {
    @Select("SELECT * FROM blog_article WHERE id=#{id} AND deleted=0 FOR UPDATE")
    Article selectForUpdate(@Param("id") long id);
}
