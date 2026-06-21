package com.mannschaft.app.appearance;

import com.mannschaft.app.appearance.dto.UpdateAppearanceRequest;
import com.mannschaft.app.appearance.entity.ThemeMode;
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
 * {@link UpdateAppearanceRequest} のバリデーション制約テスト（PUT 400 契約の根拠）。
 *
 * <p>受け入れ条件「PUT theme不正値400 / PUT bgColor形式不正400 / 必須項目欠落400」を検証する。
 * Controller は {@code @Valid @RequestBody}（AppearanceSettingsController:59）を付与しており、
 * 制約違反は Spring MVC が {@code MethodArgumentNotValidException} に変換し GlobalExceptionHandler が 400 を返す。
 * 本テストはその制約自体が発火することを純 UT（jakarta Validator・Docker不要）で担保する。
 *
 * <p>※ Controller 直呼びの統合テストでは {@code @Valid} も Jackson デシリアライズも走らないため、
 * バリデーション境界はこの Validator UT で検証する。</p>
 */
@DisplayName("UpdateAppearanceRequest バリデーション制約")
class UpdateAppearanceRequestValidationTest {

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

    private UpdateAppearanceRequest validRequest() {
        return UpdateAppearanceRequest.builder()
                .theme(ThemeMode.DARK)
                .bgColor("#1a1a2e")
                .seasonalThemeId(7L)
                .hideChatPreview(true)
                .build();
    }

    @Test
    @DisplayName("正常系: 制約違反なし")
    void valid_noViolations() {
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(validRequest());
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("bgColor形式不正400: #RRGGBB 以外は @Pattern 違反")
    void invalidBgColor_violatesPattern() {
        UpdateAppearanceRequest req = validRequest();
        req.setBgColor("red"); // HEX でない
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("bgColor");
    }

    @Test
    @DisplayName("bgColor形式不正400: 3桁HEX(#ZZZ含む)も @Pattern 違反")
    void invalidShortHexBgColor_violatesPattern() {
        UpdateAppearanceRequest req = validRequest();
        req.setBgColor("#ZZZ");
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("bgColor");
    }

    @Test
    @DisplayName("theme必須400: theme=null は @NotNull 違反")
    void nullTheme_violatesNotNull() {
        UpdateAppearanceRequest req = validRequest();
        req.setTheme(null);
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("theme");
    }

    @Test
    @DisplayName("bgColor必須400: bgColor=null は @NotNull 違反")
    void nullBgColor_violatesNotNull() {
        UpdateAppearanceRequest req = validRequest();
        req.setBgColor(null);
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("bgColor");
    }

    @Test
    @DisplayName("hideChatPreview必須400: null は @NotNull 違反")
    void nullHideChatPreview_violatesNotNull() {
        UpdateAppearanceRequest req = validRequest();
        req.setHideChatPreview(null);
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(req);
        assertThat(violations)
                .extracting(v -> v.getPropertyPath().toString())
                .contains("hideChatPreview");
    }

    @Test
    @DisplayName("seasonalThemeId は null 許容（制約違反にならない）")
    void nullSeasonalThemeId_isAllowed() {
        UpdateAppearanceRequest req = validRequest();
        req.setSeasonalThemeId(null);
        Set<ConstraintViolation<UpdateAppearanceRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }
}
