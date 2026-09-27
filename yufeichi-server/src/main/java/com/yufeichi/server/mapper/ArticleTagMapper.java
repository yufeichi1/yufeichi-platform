package com.yufeichi.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.yufeichi.server.entity.ArticleTag;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface ArticleTagMapper extends BaseMapper<ArticleTag> {
    @Insert("""
            <script>INSERT INTO blog_article_tag(article_id,tag_id) VALUES
            <foreach collection="tagIds" item="tagId" separator=",">(#{articleId},#{tagId})</foreach>
            </script>
            """)
    int insertTags(@Param("articleId") long articleId, @Param("tagIds") List<Long> tagIds);

    @Select("""
            SELECT COUNT(*) FROM blog_article_tag atg JOIN blog_article a ON a.id=atg.article_id
            WHERE atg.tag_id=#{tagId} AND a.deleted=0
            """)
    long countLiveArticles(@Param("tagId") long tagId);
}
