package com.mannschaft.app.role.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UserPermissionGroupAssignRequestValidationTest {

    @Test
    void nullGroupIdsはValidation違反になる() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new UserPermissionGroupAssignRequest(null)))
                    .singleElement()
                    .satisfies(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("groupIds"));
        }
    }

    @Test
    void emptyGroupIdsは有効() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();

            assertThat(validator.validate(new UserPermissionGroupAssignRequest(List.of())))
                    .isEmpty();
        }
    }
}
