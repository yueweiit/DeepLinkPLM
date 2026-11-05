package com.yuewei.plm.module.manufacturing;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/manufacturing-order-snapshots")
public class ManufacturingOrderSnapshotController {
    private final ManufacturingOrderSnapshotService service;
    @PostMapping("/preview") public ResponseVO<?> preview(@RequestBody ManufacturingOrderSnapshotService.Input input,HttpServletRequest request) {
        return ResponseVO.success(service.preview(input),RequestIdUtil.getRequestId(request),OffsetDateTime.now());
    }
}
