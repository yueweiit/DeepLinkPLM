package com.yuewei.plm.module.project.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.security.CurrentUser;
import com.yuewei.plm.module.bom.service.*;
import com.yuewei.plm.module.bom.entity.ProductBomCostSnapshot;
import com.yuewei.plm.module.development.DevelopmentFinanceService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DevelopmentCostScopesTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);final PilotAccessService access=mock(PilotAccessService.class);
    final DevelopmentFinanceService finance=mock(DevelopmentFinanceService.class);final ProductDevelopmentService service=mock(ProductDevelopmentService.class);final ProductBomWorkflowService bom=mock(ProductBomWorkflowService.class);
    final DevelopmentWorkflowStore store=new DevelopmentWorkflowStore(jdbc,new ObjectMapper().findAndRegisterModules());
    final DevelopmentUnitCostWorkflow workflow=new DevelopmentUnitCostWorkflow(access,new UnitCostSheetCalculator(new BomCostCalculator()),bom,jdbc,store,finance);
    final Map<String,Object> state=new LinkedHashMap<>();
    @BeforeEach void setup(){var actor=mock(CurrentUser.class);when(actor.displayName()).thenReturn("合成工程师");when(access.actor()).thenReturn(actor);when(jdbc.queryForObject(startsWith("select exists"),eq(Boolean.class),anyLong())).thenReturn(true);when(jdbc.queryForObject(startsWith("select (select count"),eq(Long.class),anyString(),anyString())).thenReturn(0L);when(finance.summary(anyString(),any())).thenReturn(Map.of("investment",Map.of("rows",List.of(),"currencyGroups",List.of())));when(service.snapshotReferences(anyLong(),anyList())).thenReturn(List.of(Map.of("type","bom","id",10L,"versionNo","B1")));when(bom.recordReviewedUnitCost(anyLong(),anyLong(),anyLong(),anyString(),any(),anyString(),anyString())).thenAnswer(a->{var s=new ProductBomCostSnapshot();s.setProductBomCostSnapshotId(50L);return s;});}
    String create(String name,long... phones){var input=new LinkedHashMap<String,Object>();input.put("name",name);input.put("phoneModelIds",Arrays.stream(phones).boxed().toList());workflow.action(20,state,"unit_cost_group_save",input,service);return input.get("savedCostScopeId").toString();}
    Map<String,Object> input(String scope,int price){var rows=new ArrayList<Map<String,Object>>();for(String group:UnitCostSheetCalculator.BASE_GROUPS)rows.add(Map.of("id",group,"category",group,"mode","unit","unitPrice",group.equals("material")?price:0,"taxIncluded",false,"taxRate",0));return new LinkedHashMap<>(Map.of("costScopeId",scope,"currency","CNY","taxBasis","net","title","合成独立成本","bomId",10L,"routeId",31L,"references",List.of(Map.of("type","bom","id",10L)),"useProjectInvestment",true,"rows",rows));}
    @Test void emptyIndependentGroupsAndCompatibilityExpansionNeverCloneProjectMoneyOrCreatePhysicalFacts(){
        state.put("unitCostDraft",Map.of("title","原项目","totals",Map.of("manufacturing",new BigDecimal("10.142"))));
        String a=create("Pro",1),b=create("Pro Max",2),c=create("Duo",3);assertThat(Set.of(a,b,c)).hasSize(3);assertThat(DevelopmentCostScopes.scopes(state)).hasSize(3).noneSatisfy(s->assertThat(s).containsKey("unitCostDraft"));
        workflow.action(20,state,"unit_cost_group_save",new LinkedHashMap<>(Map.of("costScopeId",a,"name","改名","phoneModelIds",List.of(1,4))),service);
        assertThat(DevelopmentCostScopes.find(state,a,false)).containsEntry("name","改名").containsEntry("phoneModelIds",List.of(1L,4L));assertThat(DevelopmentCostScopes.scopes(state)).hasSize(3);assertThat(state.get("unitCostDraft").toString()).contains("10.142");verifyNoInteractions(bom);verify(finance,never()).createBudget(any(),any());
    }
    @Test void draftsConfirmedVersionsAndCanceledLocalCopiesStayInTheirOwnScopeAfterRoundTrip(){
        String a=create("Pro",1),b=create("Pro Max",2);workflow.action(20,state,"unit_cost_save",input(a,5),service);workflow.action(20,state,"unit_cost_save",input(b,7),service);
        workflow.action(20,state,"unit_cost_confirm",input(a,5),service);workflow.action(20,state,"unit_cost_confirm",input(b,7),service);
        var reopened=DevelopmentWorkflowStore.map(store.read(store.encode(state)));var av=LaunchReviewWorkflow.records(DevelopmentCostScopes.find(reopened,a,false).get("unitCostVersions")).get(0);var bv=LaunchReviewWorkflow.records(DevelopmentCostScopes.find(reopened,b,false).get("unitCostVersions")).get(0);
        assertThat(av).containsEntry("id",1).containsEntry("costScopeId",a);assertThat(bv).containsEntry("id",2).containsEntry("costScopeId",b);assertThat(((Map<?,?>)av.get("totals")).get("base").toString()).isEqualTo("5");assertThat(((Map<?,?>)bv.get("totals")).get("base").toString()).isEqualTo("7");assertThat(reopened).doesNotContainKey("unitCostVersions");
        assertThat(DevelopmentCostScopes.options(reopened)).extracting(o->o.get("key")).containsExactly(a+":1",b+":2");
        verify(bom).recordReviewedUnitCost(eq(20L),eq(10L),eq(31L),eq("CNY"),any(),anyString(),eq(a));verify(bom).recordReviewedUnitCost(eq(20L),eq(10L),eq(31L),eq("CNY"),any(),anyString(),eq(b));
    }
    @Test void releaseRequiresTheRightScopesAndPreservesLegacyVersionIdentity(){
        state.put("unitCostVersions",List.of(Map.of("id",8,"title","历史")));String a=create("Pro",1),b=create("Pro Max",2);workflow.action(20,state,"unit_cost_confirm",input(a,5),service);workflow.action(20,state,"unit_cost_confirm",input(b,7),service);
        create("Duo",3);var draft=new LinkedHashMap<String,Object>(Map.of("phoneModelIds",List.of(1,2),"unitCostVersionId",8,"unitCostKeys",List.of(a+":9")));
        assertThatThrownBy(()->DevelopmentCostScopes.releaseCosts(state,draft,true)).hasMessageContaining("未采用对应成本版本");draft.put("unitCostKeys",List.of(a+":9",b+":10"));assertThat(DevelopmentCostScopes.releaseCosts(state,draft,true)).hasSize(2);
        draft.put("unitCostKeys",List.of(a+":9"));draft.put("phoneModelIds",List.of(1,99));assertThatThrownBy(()->DevelopmentCostScopes.releaseCosts(state,draft,true)).hasMessageContaining("未采用对应成本版本");draft.put("phoneModelIds",List.of(1));draft.put("unitCostKeys",List.of(a+":9"));assertThat(DevelopmentCostScopes.releaseCosts(state,draft,true)).hasSize(1);
        draft.put("unitCostKeys",List.of(b+":9"));assertThatThrownBy(()->DevelopmentCostScopes.releaseCosts(state,draft,true)).hasMessageContaining("不在本批");draft.put("unitCostKeys",List.of(a+":10"));assertThatThrownBy(()->DevelopmentCostScopes.releaseCosts(state,draft,true)).hasMessageContaining("版本不存在");assertThat(LaunchReviewWorkflow.records(state.get("unitCostVersions"))).extracting(c->c.get("id")).containsExactly(8);
    }
    @Test void anUnrelatedDuoKeepsTheExplicitLegacyVersionForTheOldBatchInBothValidationPaths(){
        var legacy=Map.<String,Object>of("id",8,"title","旧成本","basisType","supplier_quote","adoptedQuote",Map.of("supplierCode","SUP-A"));state.put("unitCostVersions",List.of(legacy));create("Duo",3);
        var draft=new LinkedHashMap<String,Object>(Map.of("phoneModelIds",List.of(1),"unitCostVersionId",8));state.put("releaseDraft",draft);
        assertThat(DevelopmentCostScopes.releaseCosts(state,draft,true)).containsExactly(legacy);assertThat(DevelopmentCostScopes.releaseCosts(state,draft,false)).containsExactly(legacy);assertThat(draft).containsEntry("unitCostVersionId",8).doesNotContainKey("unitCostKeys");
        draft.put("phoneModelIds",List.of(3));assertThat(DevelopmentCostScopes.releaseCosts(state,draft,true)).containsExactly(legacy);
    }
    @Test void theChosenSpecificationDoesNotRequireAnotherCostGroupForTheSamePhoneAndDifferentBom(){
        String large=create("大孔",1),precise=create("精孔",1);workflow.action(20,state,"unit_cost_confirm",input(large,5),service);
        when(service.snapshotReferences(anyLong(),anyList())).thenReturn(List.of(Map.of("type","bom","id",99L,"versionNo","B2")));var other=input(precise,7);other.put("bomId",99L);other.put("references",List.of(Map.of("type","bom","id",99L)));workflow.action(20,state,"unit_cost_confirm",other,service);
        var draft=Map.<String,Object>of("phoneModelIds",List.of(1),"unitCostKeys",List.of(large+":1"),"references",List.of(Map.of("type","bom","id",10L)));
        assertThat(DevelopmentCostScopes.releaseCosts(state,draft,true)).singleElement().satisfies(c->assertThat(c).containsEntry("bomId",10L).containsEntry("costScopeId",large));assertThat(DevelopmentCostScopes.releaseCosts(state,draft,false)).hasSize(1);
    }
    @Test void usedGroupsAreDisabledAndRestoredWhileUnknownOrForeignScopeWritesAreRejected(){
        String a=create("Pro",1);workflow.action(20,state,"unit_cost_save",input(a,5),service);assertThatThrownBy(()->workflow.action(20,state,"unit_cost_group_delete",new LinkedHashMap<>(Map.of("costScopeId",a)),service)).hasMessageContaining("已有核算记录");
        workflow.action(20,state,"unit_cost_group_status",new LinkedHashMap<>(Map.of("costScopeId",a,"inactive",true)),service);assertThatThrownBy(()->workflow.action(20,state,"unit_cost_save",input(a,6),service)).hasMessageContaining("已停用");workflow.action(20,state,"unit_cost_group_status",new LinkedHashMap<>(Map.of("costScopeId",a,"inactive",false)),service);workflow.action(20,state,"unit_cost_save",input(a,6),service);
        assertThatThrownBy(()->workflow.action(20,state,"unit_cost_save",input(UUID.randomUUID().toString(),99),service)).hasMessageContaining("不属于本项目");String empty=create("空组");workflow.action(20,state,"unit_cost_group_delete",new LinkedHashMap<>(Map.of("costScopeId",empty)),service);assertThat(DevelopmentCostScopes.scopes(state)).hasSize(1);
    }
}
