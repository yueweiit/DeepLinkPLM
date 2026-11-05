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
    @org.springframework.beans.factory.annotation.Autowired private com.yuewei.plm.module.manufacturing.ManufacturingIdentityService manufacturing;
    public record ConnectorReservation(String requestId,String tipo,String model,Long phoneCodeItemId,String intent,Long productLineId,Long phoneModelId,String holeType,String structureDescription,Long relationId,Long relationVersion) {}
    @PostMapping("/mold-code-reservations") public ResponseVO<?> reserve(@RequestHeader(value="Authorization",required=false) String token,@RequestBody ConnectorReservation body,HttpServletRequest r){
        authenticate(token);if(!props.isCentralCodeEnabled())throw new BusinessException(50301,"中央编码服务尚未启用");
        if(body==null||body.requestId()==null||body.requestId().isBlank())throw MoldApplicationForms.invalid("请提供稳定的编码请求ID");
        if(!java.util.Set.of("new","reuse").contains(java.util.Objects.toString(body.intent(),"")))return pending(r,"请明确新开或沿用及工程依据，不按手机名称自动生成新编码");
        if(body.productLineId()==null||body.phoneModelId()==null)throw MoldApplicationForms.invalid("请明确实际款式和业务手机");
        var config=codes.config(body.tipo());long line=((Number)config.get("productLineId")).longValue();
        if(line!=body.productLineId())throw MoldApplicationForms.invalid("连接器产品与Tipo配置不一致");
        if("reuse".equals(body.intent())&&(body.relationId()==null||body.relationVersion()==null))throw MoldApplicationForms.invalid("沿用必须明确工程确认关系及版本");
        if("new".equals(body.intent())&&body.relationId()!=null)throw MoldApplicationForms.invalid("新开与沿用依据不能混用");
        // Token authentication is not an engineering user grant. A missing integration identity never allocates a code.
        var result=manufacturing.resolveForConnector(new com.yuewei.plm.module.manufacturing.ManufacturingIdentityService.ResolveInput(line,body.phoneModelId(),body.holeType(),body.structureDescription(),body.relationId(),body.relationVersion()),line);
        var response=new java.util.LinkedHashMap<>(result);response.put("resolutionStatus",result.get("status"));response.put("materialResolutionStatus","pending");response.put("externalWriteEnabled",false);
        return ResponseVO.success(response,RequestIdUtil.getRequestId(r),OffsetDateTime.now());
    }
    private ResponseVO<?> pending(HttpServletRequest r,String reason){return ResponseVO.success(Map.of("resolutionStatus","pending","materialResolutionStatus","pending","reason",reason,"externalWriteEnabled",false),RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
    @PostMapping("/mold-events") public ResponseVO<?> event(@RequestHeader(value="Authorization",required=false) String token,@RequestBody Event body,HttpServletRequest r){authenticate(token);return ResponseVO.success(service.callback(body.processInstanceId()!=null?body.processInstanceId():body.instanceId(),body.processCode(),body.approved()),RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
}
