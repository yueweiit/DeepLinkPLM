package com.yuewei.plm.common.util;

import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.common.exception.GlobalExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Every scenario uses real PostgreSQL and its own schema; the cloned projects remain untouched. */
@EnabledIfEnvironmentVariable(named="PLM_PRODUCT_CODE_TEST_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/plm_product_code_qa")
class ProductCodeGeneratorLocalIntegrationTest {
    JdbcTemplate admin,jdbc;DriverManagerDataSource source;String schema;
    @BeforeEach void setup(){
        admin=new JdbcTemplate(new DriverManagerDataSource(System.getenv("PLM_PRODUCT_CODE_TEST_URL"),"postgres",""));
        schema="prd_number_"+UUID.randomUUID().toString().replace("-","");admin.execute("create schema "+schema);
        source=new DriverManagerDataSource(System.getenv("PLM_PRODUCT_CODE_TEST_URL")+"?currentSchema="+schema+",public","postgres","");jdbc=new JdbcTemplate(source);
        jdbc.execute("create table plm_product(product_id bigserial primary key,product_code varchar(80) not null,product_name text not null,deleted_flag int not null default 0,constraint uk_plm_product_code unique(product_code))");
    }
    @AfterEach void cleanup(){if(schema!=null)admin.execute("drop schema "+schema+" cascade");}
    void migrate()throws Exception{try(var in=getClass().getResourceAsStream("/db/migration/V20261023_1800__durable_product_code_numbers.sql")){jdbc.execute(new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8));}}
    void save(String code,int deleted){jdbc.update("insert into plm_product(product_code,product_name,deleted_flag) values(?,?,?)",code,"原始项目:"+code,deleted);}
    List<Map<String,Object>> rows(){return jdbc.queryForList("select * from plm_product order by product_id");}
    void next(long n){jdbc.queryForObject("select setval('plm_product_code_seq',?,false)",Long.class,n);}
    ProductCodeGenerator generator(){return new ProductCodeGenerator(jdbc);}

    @Test void migrationIncludesDeletedHistoricalMaximumAndDoesNotRewriteAnyProject()throws Exception{
        save("PRD-旧编码-0018",0);save("PRD-已删除-9999",1);save("NFA4030129101",0);var original=rows();migrate();
        assertThat(rows()).isEqualTo(original);assertThat(generator().generate("旧编码")).isEqualTo("PRD-旧编码-10000");assertThat(rows()).isEqualTo(original);
    }
    @Test void newGeneratorInstancesAndRolledBackReservationsNeverReuseNumbers()throws Exception{
        migrate();String first=generator().generate("透甲");save(first,0);
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));String unused=tx.execute(status->{String code=new ProductCodeGenerator(new JdbcTemplate(source)).generate("透甲");save(code,0);status.setRollbackOnly();return code;});
        String after=new ProductCodeGenerator(new JdbcTemplate(source)).generate("透甲");save(after,0);
        assertThat(List.of(first,unused,after)).containsExactly("PRD-透甲-0001","PRD-透甲-0002","PRD-透甲-0003");assertThat(rows()).hasSize(2);
    }
    @Test void parallelInstancesAllocateAndPersistUniqueCodes()throws Exception{
        migrate();int threads=8,each=20;var pool=Executors.newFixedThreadPool(threads);var start=new CountDownLatch(1);List<Future<Set<String>>> tasks=new ArrayList<>();
        try{
            for(int t=0;t<threads;t++)tasks.add(pool.submit(()->{start.await();var db=new JdbcTemplate(source);var gen=new ProductCodeGenerator(db);Set<String> values=new HashSet<>();for(int i=0;i<each;i++){String code=gen.generate("并发项目");db.update("insert into plm_product(product_code,product_name) values(?,?)",code,"并发原业务写入");assertThat(values.add(code)).isTrue();}return values;}));
            start.countDown();Set<String> all=new HashSet<>();for(var task:tasks)for(String code:task.get(40,TimeUnit.SECONDS))assertThat(all.add(code)).isTrue();
            assertThat(all).hasSize(threads*each);assertThat(jdbc.queryForObject("select count(distinct product_code) from plm_product",Integer.class)).isEqualTo(threads*each);
        }finally{pool.shutdownNow();assertThat(pool.awaitTermination(5,TimeUnit.SECONDS)).isTrue();}
    }
    @Test void manuallyImportedHighCodesAreSkippedByFullIdentityIncludingDeletedCodes()throws Exception{
        migrate();save("PRD-FUTURE-0050",1);save("PRD-FUTURE-0051",0);next(50);
        assertThat(generator().generate("future")).isEqualTo("PRD-FUTURE-0052");assertThat(rows()).hasSize(2);
    }
    @Test void anotherPrefixWithSameNumericSuffixDoesNotOccupyTheCandidate()throws Exception{
        migrate();save("PRD-FIRST-0001",0);assertThat(generator().generate("second")).isEqualTo("PRD-SECOND-0001");
    }
    @Test void legacyPrefixCleaningAndMinimumFourDigitPaddingRemainStable()throws Exception{
        migrate();assertThat(generator().generate(" a-b_12.中国更多 ")).isEqualTo("PRD-AB12中国-0001");assertThat(generator().generate(null)).isEqualTo("PRD-PLM-0002");assertThat(generator().generate("  ")).isEqualTo("PRD-PLM-0003");
    }
    @Test void numbersBeyond9999AndIntegerRangeAreNeverTruncatedOrWrapped()throws Exception{
        migrate();next(9999);assertThat(generator().generate("范围")).isEqualTo("PRD-范围-9999");assertThat(generator().generate("范围")).isEqualTo("PRD-范围-10000");next(2147483648L);assertThat(generator().generate("范围")).isEqualTo("PRD-范围-2147483648");
    }
    @Test void longOrOutOfRangeHistoricalSuffixesCannotCrashMigrationOrChangeRawCodes()throws Exception{
        save("PRD-ZERO-000000000000000000000000000000000000001001",1);save("PRD-LARGE-999999999999999999999999999999999999999999999999999999",0);save("PRD-NONNUM-abc",0);var original=rows();migrate();
        assertThat(generator().generate("新项目")).isEqualTo("PRD-新项目-1002");assertThat(rows()).isEqualTo(original);
    }
    @Test void exhaustedBigintSequenceFailsClearlyWithoutWrappingOrMemoryFallback()throws Exception{
        save("PRD-LAST-9223372036854775807",1);migrate();assertThatThrownBy(()->generator().generate("范围")).isInstanceOf(BusinessException.class).hasMessageContaining("编号已用尽");assertThat(rows()).hasSize(1);
    }
    @Test void aMissingSequenceDoesNotFallBackToAnInMemoryCounter()throws Exception{
        migrate();jdbc.execute("drop sequence plm_product_code_seq");assertThatThrownBy(()->generator().generate("失败")).isInstanceOf(DataAccessException.class);assertThat(rows()).isEmpty();
    }
    @Test void exactManualConflictUsesExistingUniqueIndexAndNarrow409Response()throws Exception{
        migrate();save("PRD-MANUAL-0001",1);DuplicateKeyException duplicate=catchThrowableOfType(()->save("PRD-MANUAL-0001",0),DuplicateKeyException.class);
        var response=new GlobalExceptionHandler().handleDuplicateKey(duplicate,new MockHttpServletRequest());assertThat(response.getStatusCode().value()).isEqualTo(409);assertThat(rows()).hasSize(1);
        DuplicateKeyException unrelated=catchThrowableOfType(()->jdbc.update("insert into plm_product(product_id,product_code,product_name) values(1,'OTHER','其他')"),DuplicateKeyException.class);
        assertThat(new GlobalExceptionHandler().handleDuplicateKey(unrelated,new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(500);
    }
}
