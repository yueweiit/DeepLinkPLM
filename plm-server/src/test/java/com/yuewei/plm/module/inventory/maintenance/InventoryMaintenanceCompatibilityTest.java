package com.yuewei.plm.module.inventory.maintenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Current PG CHECK permits six types; this policy test models an older retained row without changing shared schema. */
class InventoryMaintenanceCompatibilityTest {
 static class LegacyJdbc extends JdbcTemplate {
  final Map<String,Object> row=new LinkedHashMap<>(Map.of("inventory_id",77L,"inventory_code","0007-LEGACY","inventory_name","原物料","inventory_type","historical_raw_material","stock_uom","pcs","version",0L,"deleted_flag",0,"lifecycle_status","active","maintenance_data","{}"));
  int writes=0;
  @Override public List<Map<String,Object>> queryForList(String sql,Object...args){return sql.startsWith("select * from plm_inventory")?List.of(row):List.of();}
  @Override public <T> List<T> queryForList(String sql,Class<T> type,Object...args){return List.of();}
  @Override public int update(String sql,Object...args){writes++;if(sql.startsWith("update plm_inventory set inventory_name")){row.put("inventory_name",args[0]);row.put("inventory_type",args[1]);row.put("specification",args[2]);row.put("stock_uom",args[3]);row.put("remark",args[5]);row.put("version",1L);}return 1;}
 }
 InventoryMaintenanceService service(LegacyJdbc jdbc){var access=mock(PilotAccessService.class);when(access.actor()).thenReturn(new CurrentUser(1L,"it","隔离工程",true));when(access.actorId()).thenReturn(1L);return new InventoryMaintenanceService(jdbc,new ObjectMapper(),access,mock(OperationLogService.class),mock(FoundationObjectReferences.class));}
 Map<String,Object> input(){return new LinkedHashMap<>(Map.of("code","0007-LEGACY","name","原物料","inventoryType","historical_raw_material","unit","pcs","version",0,"remark","补充说明","reason","只更正备注","requestId",UUID.randomUUID().toString()));}
 @Test void historicalTypeCanBeKeptWhenCorrectingDescription(){var jdbc=new LegacyJdbc();var saved=service(jdbc).save(77L,false,input());assertThat(saved).containsEntry("inventoryType","historical_raw_material").containsEntry("remark","补充说明");assertThat(jdbc.row.get("inventory_type")).isEqualTo("historical_raw_material");assertThat(jdbc.writes).isEqualTo(2);}
 @Test void newMasterCannotIntroduceAnUnknownType(){var jdbc=new LegacyJdbc();assertThatThrownBy(()->service(jdbc).save(null,false,input())).hasMessageContaining("物料类型无效");assertThat(jdbc.writes).isZero();}
}
