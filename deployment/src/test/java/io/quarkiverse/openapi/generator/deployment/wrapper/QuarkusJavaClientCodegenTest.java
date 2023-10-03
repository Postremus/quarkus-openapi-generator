package io.quarkiverse.openapi.generator.deployment.wrapper;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.quarkiverse.openapi.generator.deployment.assertions.Assertions;

class QuarkusJavaClientCodegenTest {

    @ParameterizedTest
    @CsvSource({
            "/status/addressStatus,String,SLASH_STATUS_SLASH_ADDRESSSTATUS",
            "$,String,DOLLAR_SYMBOL",
            "/users,Strubg,SLASH_USERS",
            "'  ',String,SPACE_SPACE",
            "123456,String,NUMBER_123456",
            "123456,Integer,NUMBER_123456",
            "123+123,Long,NUMBER_123PLUS_123"
    })
    void toEnumVarName(String value, String dataType, String expectedVarName) {

        QuarkusJavaClientCodegen quarkusJavaClientCodegen = new QuarkusJavaClientCodegen();

        String varName = quarkusJavaClientCodegen.toEnumVarName(value, dataType);

        Assertions.assertThat(varName).isEqualTo(expectedVarName);
    }
}
