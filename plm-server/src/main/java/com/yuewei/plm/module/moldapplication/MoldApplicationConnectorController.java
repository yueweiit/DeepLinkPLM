package com.yuewei.plm.module.moldapplication;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/integrations/dingtalk")
public class MoldApplicationConnectorController {
    private final MoldApplicationProperties props;private final MoldApplicationCodes codes;private final MoldApplicationService service;
    void authenticate(String authorization){if(props.getConnectorToken().isBlank()||authorization==null||!MessageDigest.isEqual(("Bearer "+props.getConnectorToken()).getBytes(StandardCharsets.UTF_8),authorization.getBytes(StandardCharsets.UTF_8)))throw new BusinessException(40101,"连接器凭证无效");service.feature();}
    public record Event(String processCode,String processInstanceId,String instanceId,boolean approved) {}
    @PostMapping("/mold-code-reservations") public ResponseVO<?> reserve(@RequestHeader(value="Authorization",required=false) String token,@RequestBody MoldApplicationCodes.Request body,HttpServletRequest r){
        authenticate(token);if(!props.isCentralCodeEnabled())throw new BusinessException(50301,"中央编码服务尚未启用");
        // Connector requests may reserve codes but cannot bind or change arbitrary PLM applications.
        var input=new MoldApplicationCodes.Request(body.requestId(),body.tipo(),body.model(),body.phoneCodeItemId(),null);
        return ResponseVO.success(codes.reserve(input,"dingtalk-connector"),RequestIdUtil.getRequestId(r),OffsetDateTime.now());
    }
    @PostMapping("/mold-events") public ResponseVO<?> event(@RequestHeader(value="Authorization",required=false) String token,@RequestBody Event body,HttpServletRequest r){authenticate(token);return ResponseVO.success(service.callback(body.processInstanceId()!=null?body.processInstanceId():body.instanceId(),body.processCode(),body.approved()),RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
}
