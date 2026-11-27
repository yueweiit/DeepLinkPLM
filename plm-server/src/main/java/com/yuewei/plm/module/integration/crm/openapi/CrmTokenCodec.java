package com.yuewei.plm.module.integration.crm.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class CrmTokenCodec {
    private final CrmOpenApiProperties properties;private final ObjectMapper json;
    public CrmTokenCodec(CrmOpenApiProperties p,ObjectMapper json){properties=p;this.json=json;}
    public String encode(Map<String,Object> value){
        try {String body=Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(value));
            return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(mac(body));
        }catch(Exception e){throw new IllegalStateException("Cannot encode CRM token",e);}
    }
    public JsonNode decode(String token,String kind,String subject,String epoch){
        try {
            if(token==null||token.length()>8192)throw new IllegalArgumentException();
            String[] parts=token.split("\\.",-1);if(parts.length!=2||!MessageDigest.isEqual(mac(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw new IllegalArgumentException();
            JsonNode node=json.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if(!kind.equals(node.path("kind").asText())||!subject.equals(node.path("subject").asText()))throw new IllegalArgumentException();
            if(!epoch.equals(node.path("epoch").asText())||node.path("expires").asLong()<Instant.now().getEpochSecond())
                throw new CrmApiException(410,kind.equals("page")?"PAGE_TOKEN_EXPIRED":"CURSOR_EXPIRED","同步令牌已过期，请重新完整查询");
            return node;
        }catch(CrmApiException e){throw e;}catch(Exception e){throw CrmApiException.invalid("令牌签名或内容无效");}
    }
    private byte[] mac(String body)throws Exception {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(properties.getSigningSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return m.doFinal(body.getBytes(StandardCharsets.UTF_8));}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
