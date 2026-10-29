package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/partner-selections")
public class PartnerSelectionController {
 private final PartnerSelectionService selections;
 private final PartnerSampleService samples;
 private ResponseVO<?> ok(Object value,HttpServletRequest request){return ResponseVO.success(value,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
 @GetMapping public ResponseVO<?> page(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(selections.page(query),request);}
 @PostMapping public ResponseVO<?> create(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.save(null,input),request);}
 @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest request){return ok(selections.detail(id),request);}
 @PutMapping("/{id}") public ResponseVO<?> save(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.save(id,input),request);}
 @GetMapping("/{id}/rows") public ResponseVO<?> rows(@PathVariable long id,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(selections.pageRows(id,query),request);}
 @GetMapping("/{id}/originals") public ResponseVO<?> originals(@PathVariable long id,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(selections.originals(id,query),request);}
 @GetMapping("/{id}/rows/{rowId}") public ResponseVO<?> row(@PathVariable long id,@PathVariable String rowId,HttpServletRequest request){return ok(selections.row(id,rowId),request);}
 @PatchMapping("/{id}/rows") public ResponseVO<?> patchRows(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.patchRows(id,input),request);}
 @PostMapping("/{id}/batch") public ResponseVO<?> batch(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.batch(id,input),request);}
 @PostMapping("/{id}/rows/transition") public ResponseVO<?> transition(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.transitionRows(id,input),request);}
 @PostMapping("/{id}/confirmation-preview") public ResponseVO<?> preview(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.confirmationPreview(id,input),request);}
 @PostMapping("/{id}/confirm") public ResponseVO<?> confirm(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(selections.confirm(id,input),request);}
 @PostMapping("/{id}/sample-receipts/preview") public ResponseVO<?> receiptPreview(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(samples.receiptPreview(id,input),request);}
 @PostMapping("/{id}/sample-receipts") public ResponseVO<?> receive(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(samples.receiveSelection(id,input),request);}
 @PostMapping("/{id}/files") public ResponseVO<?> upload(@PathVariable long id,@RequestParam MultipartFile file,@RequestParam(defaultValue="cooperation_cost") String purpose,@RequestParam(required=false) Long previous,@RequestParam(defaultValue="V1") String version,@RequestParam(defaultValue="") String reason,HttpServletRequest request){
  return ok(selections.upload(id,file,purpose,previous,version,reason,request),request);
 }
}
