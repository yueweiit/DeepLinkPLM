package com.yuewei.plm.module.moldsales;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
class MoldSalesHistoryReviewStoreTest {
 @TempDir Path root;
 @Test void preparedFilesMustHavePinnedHashNameAndCannotEscapeThroughSymlinks()throws Exception{
  Files.createDirectory(root.resolve("history-policies"));String sha="a".repeat(64),name="history-policy-"+sha+".json";Path file=root.resolve("history-policies").resolve(name);Files.writeString(file,"{}");assertThat(MoldSalesHistoryReviewStore.source(root.toString(),new MoldSalesHistoryReviewStore.SourceRequest(name,sha))).isEqualTo(file.toRealPath());
  for(String n:new String[]{"../"+name,"/tmp/"+name,"history-policy-b.json",name+".bak"})assertThatThrownBy(()->MoldSalesHistoryReviewStore.source(root.toString(),new MoldSalesHistoryReviewStore.SourceRequest(n,sha))).hasMessageContaining("来源无效");
  Files.delete(file);Path outside=Files.createTempFile(root,"outside-",".json");Files.createSymbolicLink(file,outside);assertThatThrownBy(()->MoldSalesHistoryReviewStore.source(root.toString(),new MoldSalesHistoryReviewStore.SourceRequest(name,sha))).hasMessageContaining("暂不可用");
 }
}
