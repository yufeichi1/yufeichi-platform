package com.yufeichi.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yufeichi.server.common.error.*;
import com.yufeichi.server.dto.*;
import com.yufeichi.server.entity.*;
import com.yufeichi.server.mapper.*;
import com.yufeichi.server.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class TaxonomyService {
    private final CategoryMapper categories;
    private final TagMapper tags;
    private final ArticleMapper articles;
    private final ArticleTagMapper articleTags;

    public List<CategoryVO> categories(boolean publishedOnly) {
        return categories.selectList(new LambdaQueryWrapper<Category>()
                .eq(publishedOnly, Category::getStatus, 1)
                .orderByAsc(Category::getSortOrder, Category::getId)).stream().map(this::categoryVO).toList();
    }

    public List<TagVO> tags(boolean publishedOnly) {
        return tags.selectList(new LambdaQueryWrapper<Tag>().eq(publishedOnly, Tag::getStatus, 1)
                .orderByAsc(Tag::getId)).stream().map(this::tagVO).toList();
    }

    @Transactional
    public CategoryVO saveCategory(Long id, CategoryWriteRequest request) {
        Category category = id == null ? new Category() : requireCategory(id);
        category.setName(request.name().trim());
        category.setSlug(request.slug());
        category.setDescription(request.description());
        category.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        category.setStatus(request.status() == null ? 1 : request.status());
        category.setDeleted(0);
        if (id == null) categories.insert(category);
        else { category.setUpdatedAt(LocalDateTime.now()); categories.updateById(category); }
        return categoryVO(category);
    }

    @Transactional
    public TagVO saveTag(Long id, TagWriteRequest request) {
        Tag tag = id == null ? new Tag() : requireTag(id);
        tag.setName(request.name().trim());
        tag.setSlug(request.slug());
        tag.setStatus(request.status() == null ? 1 : request.status());
        tag.setDeleted(0);
        if (id == null) tags.insert(tag);
        else { tag.setUpdatedAt(LocalDateTime.now()); tags.updateById(tag); }
        return tagVO(tag);
    }

    @Transactional
    public void deleteCategory(long id) {
        requireCategory(id); // Same row lock acquired when articles assign this category.
        if (articles.selectCount(new LambdaQueryWrapper<Article>().eq(Article::getCategoryId,id)) > 0)
            throw new BusinessException(ErrorCode.CONFLICT,"分类仍被文章引用");
        categories.deleteById(id);
    }

    @Transactional
    public void deleteTag(long id) {
        requireTag(id);
        if (articleTags.countLiveArticles(id) > 0)
            throw new BusinessException(ErrorCode.CONFLICT,"标签仍被文章引用");
        tags.deleteById(id);
    }

    private Category requireCategory(long id) {
        Category category=categories.selectForUpdate(id);
        if(category==null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return category;
    }
    private Tag requireTag(long id) {
        Tag tag=tags.selectForUpdate(id);
        if(tag==null) throw new BusinessException(ErrorCode.NOT_FOUND);
        return tag;
    }
    private CategoryVO categoryVO(Category c) {
        return new CategoryVO(c.getId(),c.getName(),c.getSlug(),c.getDescription(),c.getSortOrder(),c.getStatus());
    }
    private TagVO tagVO(Tag t) { return new TagVO(t.getId(),t.getName(),t.getSlug(),t.getStatus()); }
}
