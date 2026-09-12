package com.mannschaft.app.auth;

import com.mannschaft.app.auth.dto.RegisterRequest;
import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 郵便番号リクエストの共通長さ制約を検証する。
 *
 * <p>対応国の形式検証とは独立して、未対応国を含む全入力経路で20文字を上限にする。</p>
 */
@DisplayName("郵便番号リクエスト長さ制約")
class PostalCodeRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("登録: 未対応国でもUnicode 20文字は許可し21文字は拒否する")
    void register_postalCodeMaximumLength() {
        assertThat(validator.validate(registerRequest("あ".repeat(20))))
                .noneMatch(violation -> violation.getPropertyPath().toString().equals("postalCode"));
        assertThat(validator.validate(registerRequest("あ".repeat(21))))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("postalCode"));
    }

    @Test
    @DisplayName("プロフィール更新: 未対応国でもUnicode 20文字は許可し21文字は拒否する")
    void updateProfile_postalCodeMaximumLength() {
        assertThat(validator.validate(updateProfileRequest("あ".repeat(20))))
                .noneMatch(violation -> violation.getPropertyPath().toString().equals("postalCode"));
        assertThat(validator.validate(updateProfileRequest("あ".repeat(21))))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("postalCode"));
    }

    private RegisterRequest registerRequest(String postalCode) {
        return new RegisterRequest(
                "postal-validation@example.com", "Password1!", "姓", "名", "表示名", postalCode,
                "fr", "Europe/Paris", null, "2000-01-01", true, "1.1.0");
    }

    private UpdateProfileRequest updateProfileRequest(String postalCode) {
        return new UpdateProfileRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, postalCode, null);
    }
}
