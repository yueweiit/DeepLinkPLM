package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.pilot.PilotAccessService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/sample-scan")
public class SampleScanController {
 private final SampleIdentityRegistry identities;private final SampleShipmentService shipments;private final PilotAccessService access;private final PartnerVisibility visibility;
 @GetMapping public Object scan(@RequestParam String code,HttpServletRequest request){Object result="mexico".equals(access.access().role())?shipments.scan(code):visibility.redact(identities.resolve(code));return ResponseVO.success(result,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
}
