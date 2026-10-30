package com.yuewei.plm.module.moldsales;
import com.yuewei.plm.common.vo.ResponseVO;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/mold-sales")
public class MoldSalesController {
    private final MoldSalesService service;
    private Object ok(Object data,HttpServletRequest r){return ResponseVO.success(data,RequestIdUtil.getRequestId(r),OffsetDateTime.now());}
    @GetMapping public Object page(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="all") String filter,@RequestParam(required=false) Long productLineId,@RequestParam(required=false) Long itemId,@RequestParam(defaultValue="sales") String view,@RequestParam(required=false) Long brandId,@RequestParam(required=false) List<Long> productLineIds,@RequestParam(required=false) List<String> productKeys,@RequestParam(defaultValue="phoneCodeDesc") String sort,HttpServletRequest r){return ok(service.page(page,size,keyword,filter,productLineId,itemId,view,brandId,productLineIds,productKeys,sort),r);}
    @GetMapping("/options") public Object options(HttpServletRequest r){return ok(service.options(),r);}
    @GetMapping("/matrix") public Object matrix(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="50") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="all") String filter,@RequestParam(required=false) Long brandId,@RequestParam(required=false) List<Long> productLineIds,@RequestParam(defaultValue="sales") String view,@RequestParam(required=false) List<String> productKeys,@RequestParam(defaultValue="phoneCodeDesc") String sort,HttpServletRequest r){return ok(service.matrix(page,size,keyword,filter,brandId,productLineIds,view,productKeys,sort),r);}
    @GetMapping("/status") public Object status(HttpServletRequest r){return ok(service.status(),r);}
    @GetMapping("/archive-sources") public Object archiveSources(HttpServletRequest r){return ok(service.archiveSources(),r);}
    @GetMapping("/archive/status") public Object archiveStatus(HttpServletRequest r){return ok(service.archiveStatus(),r);}
    @PostMapping("/archive-import") public Object archiveImport(@RequestBody MoldSalesArchiveStore.ImportRequest request,HttpServletRequest r){return ok(service.importArchive(request),r);}
    @PostMapping("/sync") public Object sync(HttpServletRequest r){return ok(service.sync(),r);}
    @PostMapping("/recalculate") public Object recalculate(HttpServletRequest r)throws Exception{return ok(service.recalculate(),r);}
    @GetMapping("/sync") public Object job(HttpServletRequest r){return ok(service.job(),r);}
    @GetMapping("/groups/{itemId}") public Object detail(@PathVariable long itemId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="sales") String view,HttpServletRequest r){return ok(service.detail(itemId,page,size,view),r);}
    @GetMapping("/source-groups/{sourceGroupKey}") public Object sourceDetail(@PathVariable String sourceGroupKey,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,@RequestParam(defaultValue="sales") String view,HttpServletRequest r){return ok(service.sourceDetail(sourceGroupKey,page,size,view),r);}
    @GetMapping("/groups/{itemId}/cost") public Object cost(@PathVariable long itemId,HttpServletRequest r){return ok(service.cost(itemId),r);}
    @PutMapping("/groups/{itemId}/cost") public Object updateCost(@PathVariable long itemId,@RequestBody MoldActualCostStore.Update update,HttpServletRequest r){return ok(service.updateCost(itemId,update),r);}
    @GetMapping("/history") public Object history(HttpServletRequest r){return ok(service.history(),r);}
    @GetMapping("/unmatched") public Object unmatched(@RequestParam(defaultValue="sales") String view,HttpServletRequest r){return ok(service.unmatched(view),r);}
    @GetMapping("/history-review/sources") public Object historyReviewSources(HttpServletRequest r){return ok(service.historyReviewSources(),r);}
    @PostMapping("/history-review/preview") public Object historyReviewPreview(@RequestBody MoldSalesHistoryReviewStore.SourceRequest input,HttpServletRequest r){return ok(service.previewHistoryReview(input),r);}
    @PostMapping("/history-review/apply") public Object historyReviewApply(@RequestBody MoldSalesHistoryReviewStore.ApplyRequest input,HttpServletRequest r){return ok(service.applyHistoryReview(input),r);}
    @GetMapping("/history-review") public Object historyReview(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="30") int size,@RequestParam(defaultValue="") String keyword,@RequestParam(defaultValue="all") String classification,HttpServletRequest r){return ok(service.historyReviewPage(page,size,keyword,classification),r);}
    @GetMapping("/history-review/orders") public Object historyReviewOrders(@RequestParam String styleCode,@RequestParam String phoneCode,@RequestParam(required=false) String sourceGroupKey,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r){return ok(service.historyReviewDetail(styleCode,phoneCode,sourceGroupKey,page,size),r);}
}
