
package com.dodaso.ecosystem.elcm.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dodaso.ecosystem.elcm.dto.WorkspaceDTO;
import com.dodaso.ecosystem.elcm.service.pipeline.WorkspaceService;

@RestController
@RequestMapping("/api/v1/pipeline/workspace")
//@RequiredArgsConstructor
public class WorkspaceController {
    
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @GetMapping("allWorkspaces")
    public List<WorkspaceDTO> allWorkspaces() {
        return workspaceService.getAllWorkspaces();
    }
}