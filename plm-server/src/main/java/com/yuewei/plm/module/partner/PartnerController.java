package com.yuewei.plm.module.partner;
import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.importexport.service.TabularSheet;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import static com.yuewei.plm.module.partner.PartnerValues.*;

@RestController @RequiredArgsConstructor
@RequestMapping({"/api/v1/partner-products","/api/v1/partner-sales","/api/v1/samples"})
public class PartnerController {
 private final PartnerQueryService query;private final PartnerProductService products;private final PartnerSalesService sales;
 private final PartnerSampleService samples;private final PartnerMaterialService materials;private final PartnerImportService imports;private final PartnerVisibility visibility;
 private String kind(HttpServletRequest r){String p=r.getRequestURI();return p.startsWith("/api/v1/partner-products")?"product":p.startsWith("/api/v1/partner-sales")?"sale":"sample";}
 private ResponseVO<?> ok(Object d,HttpServletRequest r){return ResponseVO.success(visibility.redact(d),RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
 @GetMapping public ResponseVO<?> page(@RequestParam Map<String,String> q,HttpServletRequest r){return ok(query.page(kind(r),q),r);}
 @GetMapping("/movements") public ResponseVO<?> movements(@RequestParam Map<String,String> q,HttpServletRequest r){require(kind(r).equals("sample"),"此入口仅供样品流转");return ok(query.movements(q),r);}
 @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest r){return ok(switch(kind(r)){case "product"->products.detail(id);case "sale"->sales.detail(id);default->samples.detail(id);},r);}
 @PostMapping public ResponseVO<?> create(@RequestBody Map<String,Object> d,HttpServletRequest r){return ok(switch(kind(r)){case "product"->products.save(null,d);case "sale"->sales.save(null,d);default->samples.create(d);},r);}
 @PutMapping("/{id}") public ResponseVO<?> save(@PathVariable long id,@RequestBody Map<String,Object> d,HttpServletRequest r){require(!kind(r).equals("sample"),"样品请通过实际流转或更正办理");return ok(kind(r).equals("product")?products.save(id,d):sales.save(id,d),r);}
 @DeleteMapping("/{id}") public ResponseVO<?> delete(@PathVariable long id,@RequestBody Map<String,Object> d,HttpServletRequest r){require(kind(r).equals("product"),"此删除入口仅供合作产品使用");return ok(products.delete(id,d),r);}
 @DeleteMapping("/{id}/specs/{specId}") public ResponseVO<?> deleteSpec(@PathVariable long id,@PathVariable long specId,@RequestBody Map<String,Object> d,HttpServletRequest r){require(kind(r).equals("product"),"此删除入口仅供合作产品使用");return ok(products.deleteSpec(id,specId,d),r);}
 @PostMapping("/{id}/actions/{action}") public ResponseVO<?> action(@PathVariable long id,@PathVariable String action,@RequestBody Map<String,Object> d,HttpServletRequest r){require(!kind(r).equals("product"),"产品通过资料维护保存");return ok(kind(r).equals("sample")?samples.action(id,action,d):sales.action(id,action,d),r);}
 @GetMapping("/sources") public ResponseVO<?> sources(@RequestParam(required=false) Long projectId,HttpServletRequest r){require(kind(r).equals("sample"),"此入口仅供样品来源选择");return ok(samples.sources(projectId),r);}
 @PostMapping("/{id}/files") public ResponseVO<?> file(@PathVariable long id,@RequestParam MultipartFile file,@RequestParam(defaultValue="material") String purpose,@RequestParam(required=false) Long previous,@RequestParam(defaultValue="V1") String version,@RequestParam(defaultValue="") String reason,HttpServletRequest r){return ok(materials.upload(id,kind(r),file,purpose,previous,version,reason,r),r);}
 @PostMapping("/import/preview") public ResponseVO<?> preview(@RequestParam MultipartFile file,HttpServletRequest r){return ok(imports.preview(kind(r),file),r);}
 @PutMapping("/import/{token}/matches") public ResponseVO<?> match(@PathVariable String token,@RequestBody List<Map<String,Object>> rows,HttpServletRequest r){return ok(imports.match(token,rows),r);}
 @PostMapping("/import/{token}/commit") public ResponseVO<?> commit(@PathVariable String token,HttpServletRequest r){return ok(imports.commit(token),r);}
 @GetMapping("/import/template") public ResponseEntity<byte[]> template(@RequestParam(defaultValue="xlsx") String format,HttpServletRequest r){
  require(!kind(r).equals("sample"),"样品请按实际收样登记");var headers=kind(r).equals("product")?PartnerImportService.PRODUCT_HEADERS:PartnerImportService.SALE_HEADERS;
  byte[] body=format.equals("csv")?("\uFEFF"+String.join(",",headers)+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8):TabularSheet.workbook(kind(r).equals("product")?"合作产品":"销售订单",headers,List.of());
  return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(kind(r)+"-template."+ (format.equals("csv")?"csv":"xlsx")).build().toString()).contentType(MediaType.APPLICATION_OCTET_STREAM).body(body);
 }
}
