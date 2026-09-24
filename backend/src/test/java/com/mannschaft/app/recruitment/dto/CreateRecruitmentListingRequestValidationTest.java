package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 新規募集 API が開催場所の未指定・空白だけの指定を受け付けないことを保証する。 */
@DisplayName("CreateRecruitmentListingRequest の開催場所バリデーション")
class CreateRecruitmentListingRequestValidationTest {

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
    @DisplayName("開催場所が null・空文字・空白だけなら location の違反になる")
    void location_mustNotBeBlank() {
        for (String location : new String[] {null, "", "  \t  "}) {
            Set<ConstraintViolation<CreateRecruitmentListingRequest>> violations =
                    validator.validate(request(location));

            assertThat(violations)
                    .extracting(violation -> violation.getPropertyPath().toString())
                    .contains("location");
        }
    }

    @Test
    @DisplayName("開催場所が入力されていれば location の違反にならない")
    void location_withText_isAccepted() {
        Set<ConstraintViolation<CreateRecruitmentListingRequest>> violations =
                validator.validate(request("市民競技場"));

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .doesNotContain("location");
    }

    private CreateRecruitmentListingRequest request(String location) {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 10, 0);
        return new CreateRecruitmentListingRequest(
                1L, null, "Test listing", null, RecruitmentParticipationType.INDIVIDUAL,
                start, start.plusHours(2), start.minusHours(1), start.minusHours(2),
                10, 1, false, null, RecruitmentVisibility.PUBLIC, location,
                null, null, null, null, null, null, null, null, null, null);
    }
}
