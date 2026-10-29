package com.yuewei.plm.module.attachment.controller;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.attachment.service.DocumentService;
import com.yuewei.plm.module.attachment.service.DocumentReferenceBrowser;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/file-center")
public class DocumentController {
    private final DocumentService service;
    private final DocumentReferenceBrowser referenceBrowser;
    private ResponseVO<?> ok(Object value,HttpServletRequest r){return ResponseVO.success(value,RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
    @GetMapping("/documents") public ResponseVO<?> page(@ModelAttribute DocumentService.Query q,HttpServletRequest r){return ok(service.page(q),r);}
    @PostMapping("/documents") public ResponseVO<?> upload(@RequestParam MultipartFile file,@RequestParam String fileCategory,
        @RequestParam(required=false) String versionNo,@RequestParam(required=false) String remark,
        @RequestParam(required=false) Long projectId,@RequestParam String references,HttpServletRequest r){
        return ok(service.upload(file,fileCategory,versionNo,remark,projectId,service.parseReferences(references),r),r);
    }
    @GetMapping("/reference-options") public ResponseVO<?> options(@RequestParam(required=false) String type,@RequestParam(required=false) String keyword,@RequestParam(required=false) Long parentId,HttpServletRequest r){return ok(service.options(type,keyword,parentId),r);}
    @GetMapping("/reference-options/page") public ResponseVO<?> referencePage(@ModelAttribute DocumentReferenceBrowser.Query query,HttpServletRequest r){return ok(referenceBrowser.page(query),r);}
    @GetMapping("/reference-options/phones") public ResponseVO<?> referencePhones(@ModelAttribute DocumentReferenceBrowser.Query query,HttpServletRequest r){return ok(referenceBrowser.phones(query),r);}
    @GetMapping("/documents/{id}/references") public ResponseVO<?> references(@PathVariable long id,HttpServletRequest r){return ok(service.references(id),r);}
    @PutMapping("/documents/{id}/references") public ResponseVO<?> edit(@PathVariable long id,@RequestBody DocumentService.ReferenceEdit dto,HttpServletRequest r){return ok(service.edit(id,dto),r);}
    @PostMapping("/documents/batch-references") public ResponseVO<?> batch(@RequestBody DocumentService.BatchEdit dto,HttpServletRequest r){service.batch(dto);return ok(null,r);}
    @GetMapping("/documents/{id}/reference-history") public ResponseVO<?> history(@PathVariable long id,HttpServletRequest r){return ok(service.history(id),r);}
    @GetMapping("/documents/{id}/pdf-options") public ResponseVO<?> pdfs(@PathVariable long id,HttpServletRequest r){return ok(service.pdfOptions(id),r);}
    @GetMapping("/project-nodes") public ResponseVO<?> nodes(@RequestParam long projectId,HttpServletRequest r){return ok(service.nodes(projectId),r);}
}
