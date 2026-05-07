package com.mannschaft.app.todo.dto;

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

/**
 * {@link TodoStatusChangeRequest} のバリデーションテスト（F02.3.1 Phase 1a）。
 *
 * <p>{@code status} と {@code statusLabelId} の少なくとも一方を必須とする
 * {@code @AssertTrue} の挙動を検証する。両方指定時の整合性チェックは Service 層で行うため、
 * DTO レベルでは両方指定はバリデーションを通過する。</p>
 */
@DisplayName("TodoStatusChangeRequest バリデーション")
class TodoStatusChangeRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @Test
    @DisplayName("正常系: status のみ指定")
    void status_only_valid() {
        TodoStatusChangeRequest req = new TodoStatusChangeRequest("OPEN", null);
        Set<ConstraintViolation<TodoStatusChangeRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("正常系: statusLabelId のみ指定")
    void label_only_valid() {
        TodoStatusChangeRequest req = new TodoStatusChangeRequest(null, 123L);
        Set<ConstraintViolation<TodoStatusChangeRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("正常系: status と statusLabelId 両方指定（DTO レベルでは通過、整合確認は Service 層）")
    void both_valid() {
        TodoStatusChangeRequest req = new TodoStatusChangeRequest("IN_PROGRESS", 123L);
        Set<ConstraintViolation<TodoStatusChangeRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("異常系: 両方 NULL は @AssertTrue 違反")
    void both_null_invalid() {
        TodoStatusChangeRequest req = new TodoStatusChangeRequest(null, null);
        Set<ConstraintViolation<TodoStatusChangeRequest>> violations = validator.validate(req);
        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getPropertyPath().toString())
                .isEqualTo("validRequest");
    }

    @Test
    @DisplayName("異常系: status が空白のみ + statusLabelId NULL は @AssertTrue 違反")
    void blank_status_and_null_label_invalid() {
        TodoStatusChangeRequest req = new TodoStatusChangeRequest("   ", null);
        Set<ConstraintViolation<TodoStatusChangeRequest>> violations = validator.validate(req);
        assertThat(violations).hasSize(1);
    }
}
