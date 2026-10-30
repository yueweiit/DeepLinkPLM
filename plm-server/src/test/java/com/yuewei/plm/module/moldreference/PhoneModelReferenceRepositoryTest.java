package com.yuewei.plm.module.moldreference;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked","rawtypes"})
class PhoneModelReferenceRepositoryTest {
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PhoneModelReferenceRepository repository=new PhoneModelReferenceRepository(jdbc);

    Map<String,Object> legacy(boolean phone) {
        var row=new LinkedHashMap<String,Object>(Map.of("id",7L,"brandId",1L,
            "sourceSystem","mes","sourceRecordId","old-7","sourceSnapshot","{\"old\":true}",
            "legacySourceSystem","mes","legacySourceRecordId","old-7","legacySourceSnapshot","{\"old\":true}"));
        if(phone)row.put("phoneModelId",7L);
        return row;
    }
    void base(boolean phone) {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(call->new ArrayList<>(List.of(legacy(phone))));
    }
    List<Map<String,Object>> read(boolean phone) {return phone?repository.allPhones():repository.allBrands();}
    void sourcePresent(Boolean... values) {
        when(jdbc.queryForObject(contains("to_regclass"),eq(Boolean.class))).thenReturn(values[0],Arrays.copyOfRange(values,1,values.length));
    }
    void currentSource() {
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of(
            "object_id",7L,"source_id","crm-7","source_snapshot","{\"crm\":true}")));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void historicalIdentityReadsDoNotRequireFutureSourceOrPlanningSchema(boolean phone) {
        base(phone);sourcePresent(false);
        assertThat(read(phone)).containsExactly(legacy(phone));
        var query=ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(query.capture(),any(RowMapper.class),any(Object[].class));
        assertThat(query.getValue()).doesNotContain("plm_master_data_source","m.mold_plan_status","m.mold_plan_updated_at");
        verify(jdbc,never()).queryForList(anyString(),any(Object[].class));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void currentCrmProvenanceUsesOneBatchAndKeepsTheOriginalSource(boolean phone) {
        base(phone);sourcePresent(true);currentSource();
        assertThat(read(phone).get(0)).containsEntry("sourceSystem","crm").containsEntry("sourceRecordId","crm-7")
            .containsEntry("sourceSnapshot","{\"crm\":true}").containsEntry("legacySourceSystem","mes")
            .containsEntry("legacySourceRecordId","old-7").containsEntry("legacySourceSnapshot","{\"old\":true}");
        var parameters=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(contains("order by object_id,source_key desc"),parameters.capture());
        assertThat(parameters.getValue()).containsExactly(phone?"phone_model":"phone_brand",7L);
    }
    @Test void sourceTableBecomingAvailableIsNotCachedAsMissing() {
        base(true);sourcePresent(false,true);currentSource();
        assertThat(read(true).get(0)).containsEntry("sourceRecordId","old-7");
        assertThat(read(true).get(0)).containsEntry("sourceRecordId","crm-7");
    }
    @Test void aFailureInAnExistingSourceTableIsNotHiddenAsLegacyData() {
        base(false);sourcePresent(true);
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenThrow(new DataAccessResourceFailureException("source unavailable"));
        assertThatThrownBy(()->read(false)).isInstanceOf(DataAccessResourceFailureException.class);
    }
    @Test void emptyIdentityPagesDoNotProbeOrLoadSourceMetadata() {
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThat(repository.allPhones()).isEmpty();
        verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class));
        verify(jdbc,never()).queryForList(anyString(),any(Object[].class));
    }
    @Test void selectionDictionaryReturnsOnlyBrandIdentityWithoutSourceOrMaintenanceDetails() {
        var expected=List.<Map<String,Object>>of(Map.of("id",1L,"brandId",1L,"name","Apple","status","enabled"));
        when(jdbc.queryForList(anyString())).thenReturn(expected);
        assertThat(repository.selectionBrands()).isSameAs(expected);
        var sql=ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture());
        assertThat(sql.getValue()).contains("b.id as \"brandId\"","b.name,b.status","order by b.name,b.id")
            .doesNotContain("source_snapshot","source_record_id","aliases","prefixes");
        verifyNoMoreInteractions(jdbc);
    }
    @Test void phonePagingAppliesBrandAndKeywordBeforeCountingAndOrdersOriginalCodesDescending() {
        var q=new PhoneModelReferenceTypes.Query();q.setBrandId(2L);q.setKeyword("0001");q.setPage(2);q.setSize(100);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(110L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        var page=repository.phones(q);
        assertThat(page.getTotalElements()).isEqualTo(110L);assertThat(page.getContent()).isEmpty();
        var count=ArgumentCaptor.forClass(String.class);var countArgs=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(count.capture(),eq(Long.class),countArgs.capture());
        assertThat(count.getValue()).contains("m.brand_id=?","c.code_value ilike ?","c.code_name ilike ?");
        assertThat(countArgs.getValue()).containsExactly(2L,"%0001%","%0001%","%0001%","%0001%","%0001%");
        var rows=ArgumentCaptor.forClass(String.class);var rowArgs=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(rows.capture(),any(RowMapper.class),rowArgs.capture());
        assertThat(rows.getValue()).contains("order by c.code_value desc nulls last,m.model_name,m.id limit ? offset ?");
        assertThat(rowArgs.getValue()).containsExactly(2L,"%0001%","%0001%","%0001%","%0001%","%0001%",100,100L);
    }

    @Test void selectorSearchUsesAllTokensAcrossBrandModelAliasesAndCodeBeforeCounting() {
        var q=new PhoneModelReferenceTypes.Query();q.setSelectionSearch(true);q.setKeyword(" Samsung  S26 ");q.setPage(2);q.setSize(50);
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(72L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        assertThat(repository.phones(q).getTotalElements()).isEqualTo(72L);
        var sql=ArgumentCaptor.forClass(String.class);var args=ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(sql.capture(),eq(Long.class),args.capture());
        assertThat(sql.getValue()).contains("regexp_replace", "unnest(b.aliases)", "jsonb_array_elements(b.prefixes)", "c.code_value,c.code_name").doesNotContain("m.brand_id=?");
        assertThat(args.getValue()).containsExactly("samsung","s26([^[:digit:]]|$)");
        verify(jdbc).query(anyString(),any(RowMapper.class),eq(new Object[]{"samsung","s26([^[:digit:]]|$)",50,50L}));
    }
    @Test void punctuationOnlySelectorSearchCannotBecomeAnUnfilteredCatalog() {
        var q=new PhoneModelReferenceTypes.Query();q.setSelectionSearch(true);q.setKeyword("% _ / ，");
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(0L);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenReturn(List.of());
        repository.phones(q);
        verify(jdbc).queryForObject(contains("and false"),eq(Long.class),eq(new Object[]{}));
    }

    @Test void currentPhoneNamesAreProjectedWithoutRenamingTheMemberOrRegisteredFactoryValue() throws Exception {
        var values=new LinkedHashMap<String,Object>();
        values.put("id",1333L);values.put("phoneModelId",1333L);values.put("brandName","HONOR");
        values.put("brandAliases",new String[]{"HR","Honor"});values.put("brandPrefixes","[]");
        values.put("modelName","HR 600 LITE 5G");values.put("originalCodeName","HR 600 LITE 5G");
        values.put("codeItemId",1322L);values.put("phoneModelCode","1298");values.put("codeDeletedFlag",0);
        values.put("codeStatus","enabled");values.put("groupProductionStatus","unknown");
        var rs=mock(java.sql.ResultSet.class);var metadata=mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(metadata);when(metadata.getColumnCount()).thenReturn(values.size());
        int column=0;for(var entry:values.entrySet()) {
            ++column;when(metadata.getColumnLabel(column)).thenReturn(entry.getKey());
            if(entry.getValue() instanceof String[] aliases) {
                var array=mock(java.sql.Array.class);when(array.getArray()).thenReturn(aliases);when(rs.getObject(column)).thenReturn(array);
            } else when(rs.getObject(column)).thenReturn(entry.getValue());
        }
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class)))
            .thenAnswer(call->List.of(((RowMapper<?>)call.getArgument(1)).mapRow(rs,0)));
        sourcePresent(false);
        assertThat(repository.phone(1333L,false)).containsEntry("modelName","HR 600 LITE 5G")
            .containsEntry("displayName","HONOR 600 LITE 5G").containsEntry("factoryName","HR 600 LITE 5G")
            .containsEntry("originalCodeName","HR 600 LITE 5G").containsEntry("phoneModelCode","1298")
            .containsEntry("codeItemId",1322L).containsEntry("phoneModelId",1333L).doesNotContainKey("brandPrefixes");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

}
