package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/samples/research-sources")
public class ResearchSampleSourceController {
    private final ResearchSampleSources sources;
    @GetMapping public ResponseVO<?> options(@RequestParam String domain,@RequestParam(required=false) Long projectId,
            @RequestParam(required=false) Long modelProductId,@RequestParam(required=false) Long phoneModelId,HttpServletRequest request) {
        return ResponseVO.success(sources.options(domain,projectId,modelProductId,phoneModelId),RequestIdUtil.getRequestId(request),OffsetDateTime.now());
    }
}
