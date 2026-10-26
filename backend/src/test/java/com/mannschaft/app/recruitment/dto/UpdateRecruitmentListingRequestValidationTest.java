package com.mannschaft.app.recruitment.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UpdateRecruitmentListingRequest の開催場所バリデーション")
class UpdateRecruitmentListingRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    @DisplayName("PATCH で開催場所を省略した場合は変更なしとして受け付ける")
    void location_null_isAccepted() {
        assertThat(locationViolations(null)).isEmpty();
    }

    @Test
    @DisplayName("PATCH で開催場所を指定する場合は空文字や空白だけを拒否する")
    void location_whenPresent_mustContainText() {
        for (String location : new String[] {"", "  \t  "}) {
            assertThat(locationViolations(location)).isNotEmpty();
        }
    }

    @Test
    @DisplayName("PATCH で空白以外を含む開催場所を受け付ける")
    void location_withText_isAccepted() {
        assertThat(locationViolations("市民競技場")).isEmpty();
    }

    private Set<ConstraintViolation<UpdateRecruitmentListingRequest>> locationViolations(String location) {
        return validator.validate(new UpdateRecruitmentListingRequest(
                null, null, null, null, null, null, null,
                null, null, null, null, null, location, null, null, null,
                null, null, null, null, null));
    }
}
