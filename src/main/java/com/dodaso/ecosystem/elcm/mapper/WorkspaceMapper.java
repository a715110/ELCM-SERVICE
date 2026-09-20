package com.dodaso.ecosystem.elcm.mapper;

import java.util.Collection;

import org.mapstruct.Condition;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import com.dodaso.ecosystem.baseline.common.mapper.LazyLoadingAwareMapper;
import com.dodaso.ecosystem.elcm.dto.WorkspaceDTO;
import com.dodaso.ecosystem.elcm.entity.pipeline.Workspace;

@Mapper
public interface WorkspaceMapper extends LazyLoadingAwareMapper {

  WorkspaceMapper INSTANCE = Mappers.getMapper(WorkspaceMapper.class);

  /**
   * Convert entity to DTO
   */
  WorkspaceDTO toDTO(Workspace workspace);

  /**
   * Convert DTO to entity
   */
  Workspace toEntity(WorkspaceDTO workspaceDTO);

  @Condition
  default boolean mapCollaborationTasks(Collection<Workspace> sourceCollection) {
    // only if it is defined to be lazy loading, then load the entities from
    // database and map.
    return isNotLazyLoaded(sourceCollection);
  }
}