package com.yufeichi.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yufeichi.server.common.error.*;
import com.yufeichi.server.common.result.PageResult;
import com.yufeichi.server.dto.*;
import com.yufeichi.server.entity.*;
import com.yufeichi.server.mapper.*;
import com.yufeichi.server.security.CurrentUser;
import com.yufeichi.server.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class ArticleService {
    private final ArticleMapper articles;
    private final CategoryMapper categories;
    private final TagMapper tags;
    private final ArticleTagMapper relations;

    public PageResult<ArticleSummaryVO> list(ArticleQuery query, boolean publicOnly) {
        var where=new LambdaQueryWrapper<Article>()
                .select(Article.class, field -> !field.getProperty().equals("content"))
                .eq(publicOnly, Article::getStatus,1)
                .eq(!publicOnly && query.getStatus()!=null,Article::getStatus,query.getStatus())
                .eq(query.getCategoryId()!=null,Article::getCategoryId,query.getCategoryId())
                .like(StringUtils.hasText(query.getKeyword()),Article::getTitle,query.getKeyword());
        if(query.getTagId()!=null) where.apply("EXISTS (SELECT 1 FROM blog_article_tag atg WHERE atg.article_id=blog_article.id AND atg.tag_id={0})",query.getTagId());
        where.orderByDesc(Article::getIsTop,Article::getPublishedAt,Article::getId);
        var page=articles.selectPage(new Page<>(query.getPageNum(),query.getPageSize()),where);
        return new PageResult<>(page.getRecords().stream().map(this::summary).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }

    public ArticleVO detail(long id, boolean publicOnly) {
        Article article=articles.selectOne(new LambdaQueryWrapper<Article>().eq(Article::getId,id)
                .eq(publicOnly,Article::getStatus,1));
        if(article==null) throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        return view(article,publicOnly);
    }

    @Transactional
    public ArticleVO save(Long id, ArticleWriteRequest request) {
        Article article=id==null ? new Article() : lock(id);
        List<Long> tagIds=request.tagIds()==null ? List.of() : request.tagIds().stream().distinct().sorted().toList();
        if(request.categoryId()!=null) {
            Category category=categories.selectForUpdate(request.categoryId());
            if(category==null || category.getStatus()!=1)
                throw new BusinessException(ErrorCode.PARAM_ERROR,"分类不存在或已禁用");
        }
        for(Long tagId:tagIds) {
            Tag tag=tags.selectForUpdate(tagId);
            if(tag==null || tag.getStatus()!=1) throw new BusinessException(ErrorCode.PARAM_ERROR,"标签不存在或已禁用");
        }
        article.setTitle(request.title().trim());
        article.setSummary(request.summary());
        article.setContent(request.content());
        article.setCoverUrl(request.coverUrl());
        article.setCategoryId(request.categoryId());
        article.setIsTop(request.isTop()==null ? 0 : request.isTop());
        article.setIsFeatured(request.isFeatured()==null ? 0 : request.isFeatured());
        if(id==null) {
            article.setAuthorId(CurrentUser.id());
            article.setStatus(0);
            article.setViewCount(0L);
            article.setDeleted(0);
            articles.insert(article);
        } else {
            article.setUpdatedAt(LocalDateTime.now());
            articles.updateById(article);
        }
        relations.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId,article.getId()));
        if(!tagIds.isEmpty()) relations.insertTags(article.getId(),tagIds);
        return view(article,false);
    }

    @Transactional
    public ArticleVO publish(long id, boolean publish) {
        Article article=lock(id);
        article.setStatus(publish ? 1 : 2);
        if(publish && article.getPublishedAt()==null) article.setPublishedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articles.updateById(article);
        return view(article,false);
    }

    @Transactional
    public void delete(long id) {
        lock(id);
        relations.delete(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId,id));
        articles.deleteById(id);
    }

    private Article lock(long id) {
        Article article=articles.selectForUpdate(id);
        if(article==null) throw new BusinessException(ErrorCode.ARTICLE_NOT_FOUND);
        return article;
    }
    private ArticleSummaryVO summary(Article a) {
        return new ArticleSummaryVO(a.getId(),a.getTitle(),a.getSummary(),a.getCoverUrl(),a.getCategoryId(),a.getAuthorId(),
                a.getStatus(),a.getIsTop(),a.getIsFeatured(),a.getViewCount(),a.getPublishedAt(),a.getCreatedAt(),a.getUpdatedAt());
    }
    private ArticleVO view(Article a, boolean publicOnly) {
        List<Long> ids=relations.selectList(new LambdaQueryWrapper<ArticleTag>().eq(ArticleTag::getArticleId,a.getId())
                .orderByAsc(ArticleTag::getTagId)).stream().map(ArticleTag::getTagId).toList();
        List<TagVO> result=ids.isEmpty() ? List.of() : tags.selectList(new LambdaQueryWrapper<Tag>().in(Tag::getId,ids)
                .eq(publicOnly,Tag::getStatus,1).orderByAsc(Tag::getId)).stream()
                .map(t->new TagVO(t.getId(),t.getName(),t.getSlug(),t.getStatus())).toList();
        return new ArticleVO(a.getId(),a.getTitle(),a.getSummary(),a.getContent(),a.getCoverUrl(),a.getCategoryId(),a.getAuthorId(),
                a.getStatus(),a.getIsTop(),a.getIsFeatured(),a.getViewCount(),a.getPublishedAt(),a.getCreatedAt(),a.getUpdatedAt(),
                result.stream().map(TagVO::id).toList(),result);
    }
}
