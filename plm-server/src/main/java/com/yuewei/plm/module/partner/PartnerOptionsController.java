package com.yuewei.plm.module.partner;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
public class PartnerOptionsController {
 private final PartnerQueryService query;private final PartnerVisibility visibility;
 private ResponseVO<?> ok(Object d,HttpServletRequest r){return ResponseVO.success(visibility.redact(d),RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping("/api/v1/partner-options") public ResponseVO<?> options(HttpServletRequest r){return ok(query.options(),r);}
 @GetMapping("/api/v1/customers/{id}/business") public ResponseVO<?> customer(@PathVariable long id,HttpServletRequest r){return ok(query.relations("customer",Long.toString(id)),r);}
 @GetMapping("/api/v1/inventories/suppliers/{code}/business") public ResponseVO<?> supplier(@PathVariable String code,HttpServletRequest r){return ok(query.relations("supplier",code),r);}
}
