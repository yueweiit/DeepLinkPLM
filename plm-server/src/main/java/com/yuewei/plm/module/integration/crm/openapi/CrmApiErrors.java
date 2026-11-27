package com.yuewei.plm.module.integration.crm.openapi;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=CrmOpenApiController.class)
public class CrmApiErrors {
    public static Map<String,Object> body(CrmApiException e,String requestId){return Map.of("error",Map.of("code",e.code(),"message",e.getMessage(),"fields",List.of()),"requestId",requestId);}
    @ExceptionHandler(CrmApiException.class)
    ResponseEntity<?> api(CrmApiException e,HttpServletRequest r){
        var response=ResponseEntity.status(e.status()).header("Cache-Control","no-store").header("X-Request-Id",RequestIdUtil.getRequestId(r));
        if(e.status()==503)response.header("Retry-After","5");return response.body(body(e,RequestIdUtil.getRequestId(r)));
    }
    @ExceptionHandler(BusinessException.class)
    ResponseEntity<?> business(BusinessException e,HttpServletRequest r){
        int status=e.getCode()==40103?403:e.getCode()==40101?401:e.getCode()==40401?404:422;
        return api(new CrmApiException(status,status==403?"FORBIDDEN":status==401?"UNAUTHENTICATED":status==404?"NOT_FOUND":"BUSINESS_RULE_VIOLATION",e.getMessage()),r);
    }
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<?> concurrent(ConcurrencyFailureException e,HttpServletRequest r){return api(new CrmApiException(503,"TEMPORARILY_UNAVAILABLE","并发事务竞争，请使用原幂等键重试"),r);}
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,org.springframework.http.converter.HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e,HttpServletRequest r){return api(CrmApiException.invalid("参数格式不正确"),r);}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception e,HttpServletRequest r){
        org.slf4j.LoggerFactory.getLogger(CrmApiErrors.class).error("CRM API failure requestId={}",RequestIdUtil.getRequestId(r),e);
        return api(new CrmApiException(500,"INTERNAL_ERROR","服务异常，请提供 requestId 排查"),r);
    }
}
