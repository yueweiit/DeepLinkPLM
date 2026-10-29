package com.yuewei.plm.module.pilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUserContext;
import java.lang.reflect.Type;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/** Protect legacy DTO write routes as well as the new finance controllers. */
@RestControllerAdvice @RequiredArgsConstructor
public class PilotMoneyRequestAdvice extends RequestBodyAdviceAdapter {
 private final PilotAccessService access;private final ObjectMapper json;
 @Override public boolean supports(MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter){return MappingJackson2HttpMessageConverter.class.isAssignableFrom(converter);}
 @Override public Object afterBodyRead(Object body,HttpInputMessage input,MethodParameter parameter,Type type,Class<? extends HttpMessageConverter<?>> converter){
  if(CurrentUserContext.get().isPresent()){
   // Only the supplier-operation controller delegates cost recording to engineering.
   // Its services still check kind, object scope, frozen state, version and mandatory audit.
   if(PilotSupplierCostPolicy.engineeringController(parameter.getContainingClass(),access))return body;
   var tree=json.valueToTree(body);
   if(!access.hasPermission("finance:write")&&PilotMoneyFields.containsProtectedValue(tree))throw new BusinessException(40103,"没有费用编辑权限，请移除请求中的受保护金额字段");
   if(PilotMoneyFields.containsConfirmation(tree))access.requireFinanceConfirm();
  }
  return body;
 }
}
