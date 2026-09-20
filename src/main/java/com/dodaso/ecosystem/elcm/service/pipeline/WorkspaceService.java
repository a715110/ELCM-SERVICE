package com.dodaso.ecosystem.elcm.service.pipeline;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.dodaso.ecosystem.elcm.dto.WorkspaceDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;
import com.dodaso.ecosystem.elcm.mapper.WorkspaceMapper;
import com.dodaso.ecosystem.elcm.repository.pipeline.WorkspaceRepository;

@Service
public class WorkspaceService {

    private final WorkspaceRepository workspaceRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    @Cacheable(value = "elcm", cacheManager = "elcmCacheManager", key = "'allWorkspaces'")
    public List<WorkspaceDTO> getAllWorkspaces() {
        List<Workspace> workspaceList = workspaceRepository.findAll();
        return workspaceList.stream()
                .filter(workspace -> Boolean.TRUE.equals(workspace.getIsActive()))
                .map(WorkspaceMapper.INSTANCE::toDTO)
                .collect(Collectors.toList());
    }
}
