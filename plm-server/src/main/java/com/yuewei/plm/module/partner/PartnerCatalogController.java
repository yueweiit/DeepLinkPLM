package com.yuewei.plm.module.partner;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partner-catalog")
public class PartnerCatalogController {
    private final PartnerCatalogService catalog;

    private ResponseVO<?> ok(Object data, HttpServletRequest request) {
        return ResponseVO.success(data, RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @GetMapping("/suppliers")
    public ResponseVO<?> suppliers(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ok(catalog.suppliers(query), request);
    }

    @GetMapping("/styles")
    public ResponseVO<?> styles(@RequestParam Map<String, String> query, HttpServletRequest request) {
        return ok(catalog.styles(query), request);
    }

    @GetMapping("/sources")
    public ResponseVO<?> sources(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(catalog.sources(query),request);}

    @PostMapping("/sources")
    public ResponseVO<?> createSource(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.createSource(input),request);}

    @GetMapping("/sources/{id}")
    public ResponseVO<?> source(@PathVariable long id,HttpServletRequest request){return ok(catalog.sourceDetail(id),request);}

    @GetMapping("/sources/{id}/files")
    public ResponseVO<?> sourceFiles(@PathVariable long id,@RequestParam Map<String,String> query,HttpServletRequest request){return ok(catalog.sourceFiles(id,query),request);}

    @PostMapping("/sources/{id}/purpose-preview")
    public ResponseVO<?> purposePreview(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.purposePreview(id,input),request);}

    @PostMapping("/sources/{id}/purpose")
    public ResponseVO<?> purpose(@PathVariable long id,@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.changePurpose(id,input),request);}

    @GetMapping("/styles/{sourceKind}/{recordId}/{rowId}")
    public ResponseVO<?> detail(@PathVariable String sourceKind, @PathVariable long recordId,
                               @PathVariable String rowId, @RequestParam String supplierCode,@RequestParam(defaultValue="false") boolean deleted,
                               HttpServletRequest request) {
        return ok(catalog.detail(sourceKind, recordId, rowId, supplierCode,deleted), request);
    }

    @PatchMapping("/styles/{sourceKind}/{recordId}/{rowId}")
    public ResponseVO<?> update(@PathVariable String sourceKind,@PathVariable long recordId,@PathVariable String rowId,
                                @RequestParam String supplierCode,@RequestBody Map<String,Object> input,HttpServletRequest request){
        return ok(catalog.updateStyle(sourceKind,recordId,rowId,supplierCode,input),request);
    }

    @PostMapping("/selection-additions")
    public ResponseVO<?> add(@RequestBody Map<String, Object> input, HttpServletRequest request) {
        return ok(catalog.addToSelection(input), request);
    }

    @PostMapping("/selection-additions/preview")
    public ResponseVO<?> preview(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.additionPreview(input),request);}

    @PostMapping("/styles/identity-preview")
    public ResponseVO<?> identityPreview(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.identityPreview(input),request);}

    @PostMapping("/styles/identities")
    public ResponseVO<?> identities(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.identify(input),request);}

    @GetMapping("/style-identities")
    public ResponseVO<?> styleIdentities(@RequestParam Map<String,String> query,HttpServletRequest request){return ok(catalog.styleIdentities(query),request);}

    @PostMapping("/styles/model-sheet-preview")
    public ResponseVO<?> modelSheetPreview(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.modelSheetPreview(input),request);}

    @PostMapping("/styles/model-sheets")
    public ResponseVO<?> modelSheets(@RequestBody Map<String,Object> input,HttpServletRequest request){return ok(catalog.saveModelSheet(input),request);}

    @PostMapping(value="/styles/model-sheet-file",consumes="multipart/form-data")
    public ResponseVO<?> modelSheetFile(@RequestParam Map<String,String> input,@RequestPart("file") MultipartFile file,HttpServletRequest request){return ok(catalog.uploadModelSheet(new java.util.LinkedHashMap<>(input),file,request),request);}
}
