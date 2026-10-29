package com.yuewei.plm.module.moldapplication;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Append observed source identities without replacing the original market evidence. */
public final class MoldApplicationSourceSnapshots {
    private MoldApplicationSourceSnapshots() {}
    public static JsonNode merge(JsonNode original,JsonNode incoming,ObjectMapper json) {
        ObjectNode result=original!=null&&original.isObject()?(ObjectNode)original.deepCopy():json.createObjectNode();
        if(incoming==null||!incoming.isObject())return result;
        incoming.fields().forEachRemaining(field->{if(!field.getKey().equals("sources")&&!result.has(field.getKey()))result.set(field.getKey(),field.getValue().deepCopy());});
        Map<String,JsonNode> sources=new LinkedHashMap<>();
        for(JsonNode snapshot:List.of(result,incoming))for(JsonNode source:snapshot.path("sources")) {
            String kind=source.path("kind").asText(source.path("sourceKind").asText());
            String id=source.path("id").asText(source.path("sourceId").asText());
            String key=kind.isBlank()||id.isBlank()?source.toString():kind+":"+id;
            sources.putIfAbsent(key,source.deepCopy());
        }
        var values=json.createArrayNode();sources.values().forEach(values::add);result.set("sources",values);return result;
    }
}
