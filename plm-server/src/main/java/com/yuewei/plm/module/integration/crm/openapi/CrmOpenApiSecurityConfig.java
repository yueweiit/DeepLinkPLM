package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.common.util.RequestIdUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class CrmOpenApiSecurityConfig {
    public static final String PREFIX="/api/integrations/v3/crm";
    @Bean @Order(1)
    SecurityFilterChain crmOpenApiSecurityChain(HttpSecurity http,CrmOpenApiProperties properties,JdbcTemplate jdbc,ObjectMapper mapper) throws Exception {
        http.securityMatcher(PREFIX,PREFIX+"/**").csrf(c->c.disable())
            .sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.anyRequest().authenticated())
            .exceptionHandling(e->e.authenticationEntryPoint((r,s,x)->write(s,r,mapper,new CrmApiException(401,"UNAUTHENTICATED","缺少有效 CRM 服务凭证"))))
            .addFilterBefore(new ServiceFilter(properties,jdbc,mapper),UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
    static void write(HttpServletResponse response,HttpServletRequest request,ObjectMapper mapper,CrmApiException error) throws IOException {
        response.setStatus(error.status());response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-Request-Id",RequestIdUtil.getRequestId(request));response.setHeader("Cache-Control","no-store");
        if(error.status()==503)response.setHeader("Retry-After","5");
        mapper.writeValue(response.getWriter(),CrmApiErrors.body(error,RequestIdUtil.getRequestId(request)));
    }
    // Constructed only inside this chain: deliberately not a servlet-filter bean.
    static final class ServiceFilter extends OncePerRequestFilter {
        private final CrmOpenApiProperties properties;private final JdbcTemplate jdbc;private final ObjectMapper mapper;
        ServiceFilter(CrmOpenApiProperties p,JdbcTemplate jdbc,ObjectMapper mapper){this.properties=p;this.jdbc=jdbc;this.mapper=mapper;}
        @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain) throws jakarta.servlet.ServletException,IOException {
            CurrentUser previous=CurrentUserContext.get().orElse(null);
            try {
                if(!properties.configured())throw CrmApiException.unavailable();
                if(r.getContentLengthLong()>65536)throw CrmApiException.invalid("请求正文过大");
                String header=r.getHeader("Authorization");
                if(header==null||!header.startsWith("Bearer ")||header.length()>4096||!validToken(header.substring(7)))
                    throw new CrmApiException(401,"UNAUTHENTICATED","服务凭证无效");
                var users=jdbc.queryForList("""
                    select u.user_id,u.username,u.display_name,u.all_permissions,
                      coalesce(a.role_code='domestic' and a.all_objects and a.permissions @> '["schedule:read"]'::jsonb
                        and a.permissions <@ '["schedule:read","schedule:write","collaboration:view","project:view","product:view"]'::jsonb,false) account_ready,
                      coalesce(a.permissions @> '["schedule:write"]'::jsonb,false)
                        and exists(select 1 from plm_collaboration_assignment where user_id=u.user_id and field_group='logistics')
                        and exists(select 1 from plm_collaboration_assignment where user_id=u.user_id and field_group='mexico') writer_ready
                    from sys_user u left join plm_pilot_account a on a.user_id=u.user_id
                    where u.user_id=? and u.status='active' and u.deleted_flag=0
                    """,properties.getServiceUserId());
                if(users.size()!=1||Boolean.TRUE.equals(users.get(0).get("all_permissions"))||!Boolean.TRUE.equals(users.get(0).get("account_ready")))
                    throw new CrmApiException(401,"UNAUTHENTICATED","服务账号不可用或配置不符合最小权限要求");
                if(properties.isWriteEnabled()&&properties.getScopes().contains("crm:schedule-dates:write")&&!Boolean.TRUE.equals(users.get(0).get("writer_ready")))
                    throw new CrmApiException(403,"FORBIDDEN","服务账号缺少日期填写职责");
                var user=users.get(0);var actor=new CurrentUser(((Number)user.get("user_id")).longValue(),user.get("username").toString(),user.get("display_name").toString(),false);
                CurrentUserContext.set(actor);
                var principal=new CrmServicePrincipal(properties.getSubjectId(),actor.userId(),Set.copyOf(properties.getScopes()));
                r.setAttribute(CrmServicePrincipal.ATTRIBUTE,principal);
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,List.of(new SimpleGrantedAuthority("ROLE_CRM_SERVICE"))));
                chain.doFilter(r,s);
            } catch(CrmApiException error){write(s,r,mapper,error);}
            catch(org.springframework.dao.DataAccessException error){
                org.slf4j.LoggerFactory.getLogger(ServiceFilter.class).error("CRM service account verification failed",error);
                write(s,r,mapper,CrmApiException.unavailable());
            }
            finally {CurrentUserContext.clear();if(previous!=null)CurrentUserContext.set(previous);r.removeAttribute(CrmServicePrincipal.ATTRIBUTE);SecurityContextHolder.clearContext();}
        }
        private boolean validToken(String token){
            try {byte[] actual=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));boolean valid=false;
                for(String expected:properties.getTokenDigests())valid|=MessageDigest.isEqual(actual,HexFormat.of().parseHex(expected));return valid;
            }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        }
    }
}
