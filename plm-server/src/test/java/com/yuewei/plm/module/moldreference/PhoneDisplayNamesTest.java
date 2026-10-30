package com.yuewei.plm.module.moldreference;

import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

class PhoneDisplayNamesTest {
    static Stream<Arguments> names() {
        return Stream.of(
            Arguments.of("HR 600 LITE 5G","HONOR",List.of("HR","Honor"),List.of(),"HONOR 600 LITE 5G","HR 600 LITE 5G"),
            Arguments.of("Honor 600 Lite 5G","HONOR",List.of("HR","Honor"),List.of(),"Honor 600 Lite 5G","HR 600 Lite 5G"),
            Arguments.of("IP 17 Air","APPLE",List.of("IP","iPhone","Apple"),List.of(new PhoneDisplayNames.Prefix("IP","iPhone")),"APPLE iPhone 17 Air","IP 17 Air"),
            Arguments.of("Apple iPhone 18 Pro Max","APPLE",List.of("IP","iPhone","Apple"),List.of(new PhoneDisplayNames.Prefix("IP","iPhone")),"Apple iPhone 18 Pro Max","IP 18 Pro Max"),
            Arguments.of("SM Galaxy A17 4G/5G","SAMSUNG",List.of("SM","Samsung"),List.of(new PhoneDisplayNames.Prefix("Galaxy","")),"SAMSUNG Galaxy A17 4G/5G","SM Galaxy A17 4G/5G"),
            Arguments.of("XM RM NOTE 17 PRO/NOTE 17 PRO MAX","XIAOMI",List.of("XM"),List.of(new PhoneDisplayNames.Prefix("XM RM","Redmi")),"XIAOMI Redmi NOTE 17 PRO/NOTE 17 PRO MAX","XM RM NOTE 17 PRO/NOTE 17 PRO MAX"),
            Arguments.of("MOTO G67 5G/G77 5G","MOTOROLA",List.of("MT","MOTO","Motorola"),List.of(),"MOTOROLA MOTO G67 5G/G77 5G","MOTO G67 5G/G77 5G"),
            Arguments.of("NP Nord CE 2 LITE 5G","NP",List.of("ONEPLUS"),List.of(),"ONEPLUS Nord CE 2 LITE 5G","NP Nord CE 2 LITE 5G")
        );
    }
    @ParameterizedTest @MethodSource("names")
    void separatesFullDisplayFromFactorySpellingWithoutChangingTheModel(String model,String brand,List<String> aliases,List<PhoneDisplayNames.Prefix> prefixes,String full,String factory) {
        assertThat(PhoneDisplayNames.fullName(model,brand,aliases,prefixes)).isEqualTo(full);
        assertThat(PhoneDisplayNames.factoryName(model,brand,aliases,prefixes)).isEqualTo(factory);
    }
    @Test void keepsNetworkRegionHoleAndCombinedSuffixesInsteadOfUsingIdentityNormalization() {
        String model="SM Galaxy A03S 164mm / A03S 166mm Gran agujero (Brasil)";
        assertThat(PhoneDisplayNames.fullName(model,"SAMSUNG",List.of("SM"),List.of()))
            .isEqualTo("SAMSUNG Galaxy A03S 164mm / A03S 166mm Gran agujero (Brasil)");
        assertThat(PhoneDisplayNames.factoryName(model,"SAMSUNG",List.of("SM"),List.of())).isEqualTo(model);
    }
    @Test void onlyReplacesCompleteDeclaredPrefixesAndDoesNotInventAnUnconfiguredShortCode() {
        var prefixes=List.of(new PhoneDisplayNames.Prefix("IP","iPhone"));
        assertThat(PhoneDisplayNames.fullName("iPad Pro 12.9 (2017)","APPLE",List.of("IP"),prefixes))
            .isEqualTo("APPLE iPad Pro 12.9 (2017)");
        assertThat(PhoneDisplayNames.factoryName("Infinix Hot 40i 4G","Infinix",List.of(),List.of()))
            .isEqualTo("Infinix Hot 40i 4G");
    }
}
