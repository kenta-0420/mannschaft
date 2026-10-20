package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.dto.CreateRuleRequest;
import com.mannschaft.app.loyalty.dto.UpdateRuleRequest;
import com.mannschaft.app.loyalty.dto.CreateRewardRequest;
import com.mannschaft.app.loyalty.dto.UpdateRewardRequest;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

/** 名称は生入力長でなくtrim後の1〜100文字を検証する。 */
class LoyaltyNameValidationTest {
    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    @AfterAll static void closeFactory() { FACTORY.close(); }

    @Test void AC02_作成ルールはtrim後100文字を受け入れる() {
        String name = "x".repeat(100);
        var body = new CreateRuleRequest("  " + name + "  ", 1, Frequency.EACH_STAFF_CONFIRMATION, false);
        assertThat(FACTORY.getValidator().validate(body)).isEmpty();
        assertThat(body.name()).isEqualTo(name);
    }
    @Test void AC02_更新ルールはtrim後100文字を受け入れる() {
        String name = "x".repeat(100);
        var body = new UpdateRuleRequest("  " + name + "  ", 1, Frequency.EACH_STAFF_CONFIRMATION, false, 0L, true);
        assertThat(FACTORY.getValidator().validate(body)).isEmpty();
        assertThat(body.name()).isEqualTo(name);
    }
    @Test void AC03_作成特典はtrim後100文字を受け入れる() {
        String name = "x".repeat(100);
        var body = new CreateRewardRequest("  " + name + "  ", null, 1);
        assertThat(FACTORY.getValidator().validate(body)).isEmpty();
        assertThat(body.name()).isEqualTo(name);
    }
    @Test void AC03_更新特典はtrim後100文字を受け入れる() {
        String name = "x".repeat(100);
        var body = new UpdateRewardRequest("  " + name + "  ", null, 1, 0L, true);
        assertThat(FACTORY.getValidator().validate(body)).isEmpty();
        assertThat(body.name()).isEqualTo(name);
    }
    @Test void AC18_trim後101文字は拒否する() {
        for (Object body : bodies("  " + "x".repeat(101) + "  ")) {
            assertThat(FACTORY.getValidator().validate(body)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
        }
    }
    @Test void AC18_空白だけの名称は拒否する() {
        for (Object body : bodies("   ")) {
            assertThat(FACTORY.getValidator().validate(body)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
        }
    }
    @Test void AC18_null名称は拒否する() {
        for (Object body : bodies(null)) {
            assertThat(FACTORY.getValidator().validate(body)).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
        }
    }
    private List<Object> bodies(String name) {
        return List.of(new CreateRuleRequest(name, 1, Frequency.EACH_STAFF_CONFIRMATION, false),
                new UpdateRuleRequest(name, 1, Frequency.EACH_STAFF_CONFIRMATION, false, 0L, true),
                new CreateRewardRequest(name, null, 1),
                new UpdateRewardRequest(name, null, 1, 0L, true));
    }
}
