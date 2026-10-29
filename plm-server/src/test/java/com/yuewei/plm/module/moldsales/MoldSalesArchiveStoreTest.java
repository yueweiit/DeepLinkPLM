package com.yuewei.plm.module.moldsales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuewei.plm.common.exception.BusinessException;
import com.yuewei.plm.module.operationlog.service.OperationLogService;
import com.yuewei.plm.module.pilot.PilotAccessService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoldSalesArchiveStoreTest {
    @TempDir Path root;
    static final String SHA="a".repeat(64),FILE="eccount-orders-"+SHA+".jsonl.gz";
    @Test void onlyPreparedBasenamesUnderTheConfiguredArchiveAreReadable()throws Exception{
        Files.createDirectory(root.resolve("archive"));Path file=Files.writeString(root.resolve("archive").resolve(FILE),"qa");
        assertThat(MoldSalesArchiveStore.source(root.toString(),new MoldSalesArchiveStore.ImportRequest(FILE,SHA))).isEqualTo(file.toRealPath());
        for(String name:List.of("../"+FILE,"/tmp/"+FILE,"eccount-orders.jsonl.gz","eccount-orders-"+"b".repeat(64)+".jsonl.gz"))assertThatThrownBy(()->MoldSalesArchiveStore.source(root.toString(),new MoldSalesArchiveStore.ImportRequest(name,SHA))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->MoldSalesArchiveStore.source(root.toString(),new MoldSalesArchiveStore.ImportRequest(FILE,"bad"))).isInstanceOf(BusinessException.class);
    }
    @Test void aSymbolicFileCannotEscapeThePreparedDirectory()throws Exception{
        Files.createDirectory(root.resolve("archive"));Path outside=Files.writeString(root.resolve("outside"),"qa");Files.createSymbolicLink(root.resolve("archive").resolve(FILE),outside);
        assertThatThrownBy(()->MoldSalesArchiveStore.source(root.toString(),new MoldSalesArchiveStore.ImportRequest(FILE,SHA))).isInstanceOf(BusinessException.class);
    }
    @Test void missingArchiveIsAnEmptyPreparedListAndNeverCreatesAFile()throws Exception{
        var jdbc=mock(JdbcTemplate.class);var store=new MoldSalesArchiveStore(jdbc,new ObjectMapper(),mock(OperationLogService.class),mock(TransactionTemplate.class),mock(PilotAccessService.class));
        assertThat(store.sources(root.toString())).isEmpty();assertThat(Files.exists(root.resolve("archive"))).isFalse();verifyNoInteractions(jdbc);
    }
    @Test void objectScopedStatusCannotExposeGlobalRowsOrSourceHash(){
        var jdbc=mock(JdbcTemplate.class);when(jdbc.queryForList(anyString())).thenReturn(List.of(Map.of("batchId",UUID.randomUUID(),"sourceHash",SHA,"file",FILE,"importedAt","2026-10-27T00:00:00Z","summary","{\"rows\":916724,\"reviewRows\":123,\"counts\":{\"unit_review\":40}}")));
        var store=new MoldSalesArchiveStore(jdbc,new ObjectMapper(),mock(OperationLogService.class),mock(TransactionTemplate.class),mock(PilotAccessService.class));
        assertThat(store.status(false)).containsExactlyInAnyOrderEntriesOf(Map.of("imported",true,"importedAt","2026-10-27T00:00:00Z"));assertThat(store.status(true)).containsKeys("rows","reviewRows","sourceHash");
    }
}
