package com.yuewei.plm.module.workbench.home;

import com.yuewei.plm.common.constant.ApiConstants;
import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping(ApiConstants.API_V1_PREFIX+"/workbench/home")
public class WorkbenchHomeController {
    private final WorkbenchHomeService service;
    @GetMapping("/summary") public ResponseVO<?> summary(HttpServletRequest request) { return response(service.summary(),request); }
    @GetMapping("/projects") public ResponseVO<?> projects(@Valid WorkbenchHomeModels.Query q,HttpServletRequest request) { return response(service.projects(q),request); }
    @GetMapping("/tasks") public ResponseVO<?> tasks(@Valid WorkbenchHomeModels.Query q,HttpServletRequest request) { return response(service.tasks(q),request); }
    @GetMapping("/approvals") public ResponseVO<?> approvals(@Valid WorkbenchHomeModels.Query q,HttpServletRequest request) { return response(service.approvals(q),request); }
    @GetMapping("/exceptions") public ResponseVO<?> exceptions(@Valid WorkbenchHomeModels.Query q,HttpServletRequest request) { return response(service.exceptions(q),request); }
    private ResponseVO<?> response(Object data,HttpServletRequest request) { return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now()); }
}
