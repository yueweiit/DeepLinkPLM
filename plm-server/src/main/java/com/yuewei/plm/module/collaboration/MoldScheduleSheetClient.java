package com.yuewei.plm.module.collaboration;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuewei.plm.module.moldapplication.MoldDingTalkClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static com.yuewei.plm.module.collaboration.CollaborationFields.invalid;

/** Reuses the configured application's bounded transport and token cache; read only. */
@Component @RequiredArgsConstructor
public class MoldScheduleSheetClient {
    public static final String NODE="Y1OQX0akWm3ZYp24tb32l4mjJGlDd3mE";
    public static final String SOURCE_URL="https://alidocs.dingtalk.com/i/nodes/"+NODE;
    private final MoldDingTalkClient client;
    public JsonNode read(String existingDingTalkUserId) {
        String union=client.user(existingDingTalkUserId).path("unionid").asText();
        if(union.isBlank())throw invalid("读取账号缺少有效钉钉身份，请核对已有账号绑定");
        String query="?operatorId="+URLEncoder.encode(union,StandardCharsets.UTF_8);
        String root="/v1.0/doc/workbooks/"+NODE+"/sheets";
        JsonNode sheets=client.modern(root+query,null).path("value");
        if(!sheets.isArray())throw invalid("钉钉未返回工作表列表，原排程保留");
        String sheet=null;
        for(JsonNode row:sheets)if("Sheet1".equals(row.path("name").asText())) {
            if(sheet!=null)throw invalid("工作表名称重复，请核对在线表结构");
            sheet=row.path("id").asText();
        }
        if(sheet==null||!sheet.matches("[A-Za-z0-9_-]{1,128}"))throw invalid("未找到已确认的 Sheet1，原排程保留");
        JsonNode result=client.modern(root+"/"+sheet+"/ranges/A1%3AK1000"+query,null);
        if(!result.path("values").isArray()&&!result.path("displayValues").isArray())throw invalid("在线表没有有效单元格数据，原排程保留");
        return result;
    }
}
