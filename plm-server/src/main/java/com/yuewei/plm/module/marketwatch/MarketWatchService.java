package com.yuewei.plm.module.marketwatch;

import com.yuewei.plm.common.constant.ErrorCodeConstants;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.common.security.CurrentUserContext;
import com.yuewei.plm.module.marketwatch.MarketWatchTypes.BulkUpdate;
import com.yuewei.plm.module.marketwatch.MarketWatchTypes.UpdateItem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

@Service
@RequiredArgsConstructor
public class MarketWatchService {
    private final MarketWatchClient client;
    private final JdbcTemplate jdbc;

    public Map<String,Object> config() {
        CurrentUser actor = actor();
        return Map.of("enabled",client.enabled(),"writable",canWrite(actor),"administrator",actor.allPermissions());
    }

    public Map<String,Object> health() { readGate(); return client.health(); }
    public Map<String,Object> update(String kind,long id,UpdateItem update) {
        writeGate();
        Map<String,Object> payload = new LinkedHashMap<>();
        if (update.status()!=null) payload.put("status",update.status());
        if (update.badge()!=null) payload.put("badge",update.badge());
        if (update.saleStatus()!=null) payload.put("saleStatus",update.saleStatus());
        return client.update(kind,id,payload);
    }

    public Map<String,Object> bulk(BulkUpdate update) {
        writeGate();
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("kind",update.kind());payload.put("ids",update.ids());
        if (update.status()!=null) payload.put("status",update.status());
        if (update.badge()!=null) payload.put("badge",update.badge());
        if (update.saleStatus()!=null) payload.put("saleStatus",update.saleStatus());
        return client.bulk(payload);
    }

    public MarketWatchClient.CsvFile export(MultiValueMap<String,String> query) { readGate(); return client.export(query); }

    private CurrentUser readGate() {
        if(!client.enabled()) throw new BusinessException(ErrorCodeConstants.RESOURCE_NOT_FOUND,"开模申请市场来源未启用");
        return actor();
    }

    private CurrentUser writeGate() {
        CurrentUser actor=readGate();
        if(!canWrite(actor)) throw new BusinessException(ErrorCodeConstants.FORBIDDEN,"只有市场负责人或管理员可以维护监控数据");
        return actor;
    }

    private CurrentUser actor() {
        CurrentUser session=CurrentUserContext.get().orElseThrow(()->new BusinessException(ErrorCodeConstants.UNAUTHORIZED,"请先登录"));
        List<Map<String,Object>> users=jdbc.queryForList(
            "select username,display_name,all_permissions from sys_user where user_id=? and deleted_flag=0 and status='active'",
            session.userId());
        if(users.isEmpty()) throw new BusinessException(ErrorCodeConstants.UNAUTHORIZED,"账号已失效");
        Map<String,Object> user=users.get(0);
        return new CurrentUser(session.userId(),Objects.toString(user.get("username"),session.username()),
            Objects.toString(user.get("display_name"),session.displayName()),Boolean.TRUE.equals(user.get("all_permissions")));
    }

    private boolean canWrite(CurrentUser actor) {
        if(actor.allPermissions()) return true;
        Long count=jdbc.queryForObject("select count(*) from plm_collaboration_assignment where field_group='market' and user_id=?",Long.class,actor.userId());
        return count!=null&&count>0;
    }

}
