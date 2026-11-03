package com.yuewei.plm.module.partner;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SampleCodesTest {
 @Test void supplierLabelKeepsSyPrefixLeadingZeroesAndTheOriginalGlobalSequence(){
  assertThat(SampleCodes.display("SY-000065","XD")).isEqualTo("SY-XD-000065");
  assertThat(SampleCodes.display("SY-1000000","mf")).isEqualTo("SY-MF-1000000");
  assertThat(SampleCodes.display("SY-000065","QA-2")).isEqualTo("SY-QA-2-000065");
 }
 @Test void unknownSupplierAndExistingSpLabelsAreNotGuessedOrReallocated(){
  assertThat(SampleCodes.display("SY-000065",null)).isEqualTo("SY-000065");
  assertThat(SampleCodes.display("SP-000066","XD")).isEqualTo("SP-000066");
 }
}
