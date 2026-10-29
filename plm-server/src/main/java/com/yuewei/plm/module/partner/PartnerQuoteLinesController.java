package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/partner-operations/supplier_quote/{id}/lines")
public class PartnerQuoteLinesController {
 private final PartnerQuoteLines lines;
 @GetMapping public ResponseVO<?> page(@PathVariable long id,@RequestParam Map<String,String> query,HttpServletRequest request){return ResponseVO.success(lines.page(id,query),RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
 @PatchMapping public ResponseVO<?> patch(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ResponseVO.success(lines.patch(id,input),RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
}
