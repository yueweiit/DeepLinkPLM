package com.yuewei.plm.module.code.controller;

import com.yuewei.plm.common.util.RequestIdUtil;
import com.yuewei.plm.common.vo.PageVO;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.module.code.dto.CodeItemQueryDTO;
import com.yuewei.plm.module.code.dto.CodeItemSaveDTO;
import com.yuewei.plm.module.code.service.CodeItemService;
import com.yuewei.plm.module.code.service.CodeItemImportService;
import com.yuewei.plm.module.code.vo.CodeImportPreviewVO;
import com.yuewei.plm.module.code.vo.CodeItemVO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewQueryDTO;
import com.yuewei.plm.module.code.dto.PhoneModelReviewConfirmDTO;
import com.yuewei.plm.module.code.vo.PhoneModelReviewVO;
import com.yuewei.plm.module.code.service.PhoneModelReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/code-items")
public class CodeItemController {
    private final CodeItemService service;
    @org.springframework.beans.factory.annotation.Autowired private com.yuewei.plm.module.code.service.CodeLifecycleService lifecycle;
    private final CodeItemImportService importService;
    private final PhoneModelReviewService reviewService;

    @GetMapping("/phone-model-review")
    public ResponseVO<PageVO<PhoneModelReviewVO>> review(@Valid PhoneModelReviewQueryDTO query, HttpServletRequest request) {
        return ResponseVO.success(reviewService.page(query), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }
    @PostMapping("/phone-model-review/confirm")
    public ResponseVO<PhoneModelReviewVO> confirmReview(@Valid @RequestBody PhoneModelReviewConfirmDTO dto,HttpServletRequest request) {
        return ResponseVO.success(reviewService.confirm(dto,request),RequestIdUtil.getRequestId(request),OffsetDateTime.now());
    }

    @GetMapping
    public ResponseVO<PageVO<CodeItemVO>> page(@Valid CodeItemQueryDTO query, HttpServletRequest request) {
        return ResponseVO.success(service.page(query), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @PostMapping
    public ResponseVO<CodeItemVO> create(@Valid @RequestBody CodeItemSaveDTO dto, HttpServletRequest request) {
        return ResponseVO.success(service.create(dto), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @PutMapping("/{id}")
    public ResponseVO<CodeItemVO> update(@PathVariable Long id, @Valid @RequestBody CodeItemSaveDTO dto,
        HttpServletRequest request) {
        return ResponseVO.success(service.update(id, dto), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @GetMapping("/{id}") public ResponseVO<?> detail(@PathVariable long id,HttpServletRequest request){return ResponseVO.success(lifecycle.detail("code",id),RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @GetMapping("/{id}/references") public ResponseVO<?> references(@PathVariable long id,HttpServletRequest request){return ResponseVO.success(lifecycle.inspect("code",id),RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    @PostMapping("/{id}/actions/{action}") public ResponseVO<?> action(@PathVariable long id,@PathVariable String action,@Valid @RequestBody com.yuewei.plm.module.code.service.CodeLifecycleService.Action input,HttpServletRequest request){return ResponseVO.success(lifecycle.act("code",id,action,input,request),RequestIdUtil.getRequestId(request),OffsetDateTime.now());}
    /** Old pages must refresh instead of bypassing reason/version checks. */
    @PostMapping({"/{id}/enable","/{id}/disable"}) public ResponseVO<?> oldStatus(@PathVariable long id,HttpServletRequest request){lifecycle.maintenance();throw new com.yuewei.plm.common.exception.BusinessException(40901,"页面已更新，请刷新后填写原因并确认操作");}

    @PostMapping(value = "/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseVO<CodeImportPreviewVO> preview(@RequestParam("file") MultipartFile file,
        HttpServletRequest request) throws java.io.IOException {
        return ResponseVO.success(importService.preview(file.getOriginalFilename(), file.getBytes()),
            RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }

    @PostMapping("/import/{token}/commit")
    public ResponseVO<CodeImportPreviewVO> commit(@PathVariable String token, HttpServletRequest request) {
        return ResponseVO.success(importService.commit(token), RequestIdUtil.getRequestId(request), OffsetDateTime.now());
    }
}
