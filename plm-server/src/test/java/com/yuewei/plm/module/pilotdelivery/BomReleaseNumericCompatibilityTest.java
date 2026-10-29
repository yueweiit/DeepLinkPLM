package com.yuewei.plm.module.pilotdelivery;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BomReleaseNumericCompatibilityTest {
    @Test void precisionWideningDoesNotInvalidateAnUnchangedHistoricalRelease(){assertTrue(BomReleaseDeliveryService.sameNumericContent("{\"quantity\":1.000000,\"id\":7}","{\"id\":7,\"quantity\":1.00000000}"));}
    @Test void realChangesStillRequireANewVersionIncludingEightDecimalDifferences(){assertFalse(BomReleaseDeliveryService.sameNumericContent("{\"quantity\":1234567890123456.12345678}","{\"quantity\":1234567890123456.12345679}"));assertFalse(BomReleaseDeliveryService.sameNumericContent("{\"quantity\":1}","{\"quantity\":2}"));}
}
