package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@RestController @RequiredArgsConstructor @RequestMapping({"/api/v1/partner-selections","/api/v1/partner-catalog/sources"})
public class PartnerSelectionImportController {
 private final PartnerSelectionImportService service;private final PartnerSelectionTransfers transfers;
 private ResponseVO<?> ok(Object data,HttpServletRequest request){return ResponseVO.success(data,RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
 @PostMapping("/{id}/uploads") public ResponseVO<?> initialize(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(transfers.initialize(id,input),request);}
 @GetMapping("/{id}/uploads/{uploadId}") public ResponseVO<?> upload(@PathVariable long id,@PathVariable UUID uploadId,HttpServletRequest request){return ok(transfers.status(id,uploadId),request);}
 @PutMapping(value="/{id}/uploads/{uploadId}/parts/{index}",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) public ResponseVO<?> part(@PathVariable long id,@PathVariable UUID uploadId,@PathVariable long index,@RequestHeader("X-Part-SHA256") String hash,HttpServletRequest request) throws IOException{return ok(transfers.part(id,uploadId,index,hash,request.getInputStream()),request);}
 @PostMapping("/{id}/uploads/{uploadId}/complete") public ResponseVO<?> complete(@PathVariable long id,@PathVariable UUID uploadId,@RequestBody(required=false) Map<String,Object> input,HttpServletRequest request){return ok(transfers.complete(id,uploadId,input==null?Map.of():input,request),request);}
 @DeleteMapping("/{id}/uploads/{uploadId}") public ResponseVO<?> cancel(@PathVariable long id,@PathVariable UUID uploadId,HttpServletRequest request){transfers.cancel(id,uploadId);return ok(Map.of("cancelled",true),request);}
 @PostMapping("/{id}/import-jobs") public ResponseVO<?> createImport(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.createImport(id,input),request);}
 @PostMapping("/{id}/export-jobs") public ResponseVO<?> createExport(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.createExport(id,input),request);}
 @GetMapping({"/{id}/import-jobs/{jobId}","/{id}/export-jobs/{jobId}"}) public ResponseVO<?> job(@PathVariable long id,@PathVariable UUID jobId,HttpServletRequest request){return ok(service.job(id,jobId),request);}
 @GetMapping("/{id}/file-jobs") public ResponseVO<?> history(@PathVariable long id,HttpServletRequest request){return ok(service.history(id),request);}
 @PostMapping({"/{id}/import-jobs/{jobId}/retry","/{id}/export-jobs/{jobId}/retry"}) public ResponseVO<?> retry(@PathVariable long id,@PathVariable UUID jobId,HttpServletRequest request){return ok(service.retry(id,jobId),request);}
 @PostMapping("/{id}/import-jobs/{jobId}/parse") public ResponseVO<?> parse(@PathVariable long id,@PathVariable UUID jobId,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.parse(id,jobId,input),request);}
 @GetMapping("/{id}/import-jobs/{jobId}/rows") public ResponseVO<?> rows(@PathVariable long id,@PathVariable UUID jobId,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.rows(id,jobId,query),request);}
 @PatchMapping("/{id}/import-jobs/{jobId}/rows/{rowId}") public ResponseVO<?> correct(@PathVariable long id,@PathVariable UUID jobId,@PathVariable UUID rowId,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.correct(id,jobId,rowId,input),request);}
 @PostMapping("/{id}/import-jobs/{jobId}/commit") public ResponseVO<?> commit(@PathVariable long id,@PathVariable UUID jobId,@RequestBody(required=false) Map<String,Object> input,HttpServletRequest request){return ok(service.commit(id,jobId,input==null?Map.of():input),request);}
 @GetMapping("/{id}/import-jobs/{jobId}/images/{imageId}") public ResponseEntity<Resource> image(@PathVariable long id,@PathVariable UUID jobId,@PathVariable UUID imageId){var image=service.image(id,jobId,imageId);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType(text(image.get("content_type")))).body(new FileSystemResource((Path)image.get("path")));}
 @GetMapping("/{id}/import-jobs/{jobId}/images") public ResponseVO<?> images(@PathVariable long id,@PathVariable UUID jobId,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(service.images(id,jobId,query),request);}
 @PatchMapping("/{id}/import-jobs/{jobId}/images/{imageId}") public ResponseVO<?> assignImage(@PathVariable long id,@PathVariable UUID jobId,@PathVariable UUID imageId,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(service.assignImage(id,jobId,imageId,input),request);}
 @GetMapping("/{id}/export-jobs/{jobId}/download") public ResponseEntity<Resource> download(@PathVariable long id,@PathVariable UUID jobId){return download(service.download(id,jobId),"选品表.xlsx");}
 /** Legacy clients now receive the same durable job, not the retired 37-column protocol. */
 @GetMapping("/{id}/export") public ResponseVO<?> export(@PathVariable long id,HttpServletRequest request){return ok(service.createExport(id,Map.of("requestId","legacy-export-"+UUID.randomUUID())),request);}
 @GetMapping("/template") public ResponseEntity<Resource> template(@RequestParam(defaultValue="") String supplierCode,@RequestParam(defaultValue="xlsx") String format){require(format.equals("xlsx"),"新版含图选品模板使用XLSX；CSV不承载图片和下拉");return download(service.template(supplierCode),"选品模板.xlsx");}
 @PostMapping("/{id}/import/preview") public ResponseVO<?> preview(@PathVariable long id,@RequestParam MultipartFile file,HttpServletRequest request){return ok(service.preview(id,file),request);}
 @PostMapping("/{id}/import/{token}/commit") public ResponseVO<?> legacyCommit(@PathVariable long id,@PathVariable String token,HttpServletRequest request){return ok(service.commit(id,token),request);}
 private ResponseEntity<Resource> download(Path path,String name){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString()).cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(new FileSystemResource(path));}
}
