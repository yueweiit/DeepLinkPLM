package com.yuewei.plm.module.project.controller;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.project.ProjectWorkbenchService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/projects")
public class ProjectBusinessOverviewController {
    private final ProjectWorkbenchService service;
    @GetMapping("/{id}/business-overview") public ResponseVO<?> overview(@PathVariable long id,HttpServletRequest request){return ResponseVO.success(service.overview(id),RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
}
