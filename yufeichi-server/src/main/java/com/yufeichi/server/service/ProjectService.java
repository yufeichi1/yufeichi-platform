package com.yufeichi.server.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yufeichi.server.common.error.*;
import com.yufeichi.server.common.result.PageResult;
import com.yufeichi.server.dto.*;
import com.yufeichi.server.entity.Project;
import com.yufeichi.server.mapper.ProjectMapper;
import com.yufeichi.server.vo.ProjectVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional(readOnly=true)
public class ProjectService {
    private final ProjectMapper projects;
    public PageResult<ProjectVO> list(ProjectQuery query,boolean publicOnly) {
        var where=new LambdaQueryWrapper<Project>()
                .eq(publicOnly,Project::getStatus,1)
                .eq(!publicOnly && query.getStatus()!=null,Project::getStatus,query.getStatus())
                .like(StringUtils.hasText(query.getKeyword()),Project::getName,query.getKeyword())
                .orderByAsc(Project::getSortOrder).orderByDesc(Project::getId);
        var page=projects.selectPage(new Page<>(query.getPageNum(),query.getPageSize()),where);
        return new PageResult<>(page.getRecords().stream().map(this::view).toList(),page.getTotal(),page.getCurrent(),page.getSize());
    }
    public ProjectVO detail(long id,boolean publicOnly) {
        Project p=projects.selectOne(new LambdaQueryWrapper<Project>().eq(Project::getId,id).eq(publicOnly,Project::getStatus,1));
        if(p==null) throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
        return view(p);
    }
    @Transactional
    public ProjectVO save(Long id,ProjectWriteRequest request) {
        Project p=id==null ? new Project() : lock(id);
        p.setName(request.name().trim()); p.setDescription(request.description());
        p.setCoverUrl(request.coverUrl()); p.setGithubUrl(request.githubUrl()); p.setDemoUrl(request.demoUrl());
        p.setTechStack(request.techStack()); p.setSortOrder(request.sortOrder()==null ? 0 : request.sortOrder());
        p.setStatus(request.status()==null ? 0 : request.status()); p.setDeleted(0);
        if(id==null) projects.insert(p);
        else { p.setUpdatedAt(LocalDateTime.now()); projects.updateById(p); }
        return view(p);
    }
    @Transactional
    @com.yufeichi.server.security.AuditAction("project.visibility")
    public ProjectVO status(long id,int status) {
        Project p=lock(id); p.setStatus(status); p.setUpdatedAt(LocalDateTime.now()); projects.updateById(p);
        return view(p);
    }
    @Transactional
    @com.yufeichi.server.security.AuditAction("project.delete")
    public void delete(long id) { lock(id); projects.deleteById(id); }
    private Project lock(long id) {
        Project p=projects.selectForUpdate(id);
        if(p==null) throw new BusinessException(ErrorCode.PROJECT_NOT_FOUND);
        return p;
    }
    private ProjectVO view(Project p) {
        return new ProjectVO(p.getId(),p.getName(),p.getDescription(),p.getCoverUrl(),p.getGithubUrl(),p.getDemoUrl(),
                p.getTechStack(),p.getSortOrder(),p.getStatus(),p.getCreatedAt(),p.getUpdatedAt());
    }
}
