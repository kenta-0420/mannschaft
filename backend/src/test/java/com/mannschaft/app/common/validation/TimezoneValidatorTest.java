package com.mannschaft.app.common.validation;

import com.mannschaft.app.auth.dto.UpdateProfileRequest;
import com.mannschaft.app.team.entity.TeamEntity;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ValidTimezone} / {@link TimezoneValidator} の単体テスト（Issue #2487 項目 4）。
 *
 * <p>「ユーザーが任意文字列を {@code users.timezone} に保存できる」穴を入口で塞いだことを課す。
 * DTO（{@link UpdateProfileRequest}）に実際に制約が載っていることまで含めて検証する
 * （アノテーションの付け忘れは制約クラス単体のテストでは検出できないため）。</p>
 */
@DisplayName("ValidTimezone 制約（users.timezone の入口検証）")
class TimezoneValidatorTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        factory.close();
    }

    private final TimezoneValidator target = new TimezoneValidator();

    // ============================================================
    // 制約クラス単体
    // ============================================================

    @ParameterizedTest(name = "有効な IANA 名 [{0}] は通る")
    @ValueSource(strings = {
            "Asia/Tokyo",
            "Asia/Kolkata",      // +05:30（30 分刻み）
            "Asia/Kathmandu",    // +05:45（45 分刻み）
            "Pacific/Apia",      // +13:00
            "Pacific/Kiritimati", // +14:00
            "America/Los_Angeles",
            "UTC",
            "  Asia/Tokyo  "     // 前後の空白は許容（trim して判定）
    })
    void 有効なIANA名は通る(String timezone) {
        assertThat(target.isValid(timezone, null)).isTrue();
    }

    @ParameterizedTest(name = "null は「未指定＝更新しない」として通る")
    @NullSource
    void nullは通る(String timezone) {
        assertThat(target.isValid(timezone, null)).isTrue();
    }

    @ParameterizedTest(name = "不正値 [{0}] は弾く")
    @ValueSource(strings = {
            "",
            "   ",
            "Foo/Bar",           // 実在しない
            "Asia/Tokyo; DROP",  // 混入
            "asia/tokyo",        // 大小文字は tzdb の表記に一致しない
            "JST",               // 非 IANA の略称
            "+09:00",            // 固定オフセット表記（夏時間を追随できない）
            "Z",
            "UTC+9",
            "GMT+09:00"
    })
    void 不正値は弾く(String timezone) {
        assertThat(target.isValid(timezone, null)).isFalse();
    }

    // ============================================================
    // DTO への結線（アノテーションの付け忘れを検出する）
    // ============================================================

    @Test
    @DisplayName("UpdateProfileRequest の timezone に不正値を入れると制約違反になる")
    void プロフィール更新DTOで不正値が違反になる() {
        UpdateProfileRequest request = requestWithTimezone("Foo/Bar");

        assertThat(validator.validate(request))
                .as("DTO に @ValidTimezone が載っていること（付け忘れ検出）")
                .anySatisfy(violation ->
                        assertThat(violation.getPropertyPath()).hasToString("timezone"));
    }

    @Test
    @DisplayName("UpdateProfileRequest の timezone が有効な IANA 名なら違反にならない")
    void プロフィール更新DTOで有効値は違反にならない() {
        assertThat(validator.validate(requestWithTimezone("Asia/Kathmandu"))).isEmpty();
    }

    @Test
    @DisplayName("UpdateProfileRequest の timezone が null（未指定）なら違反にならない")
    void プロフィール更新DTOでnullは違反にならない() {
        assertThat(validator.validate(requestWithTimezone(null))).isEmpty();
    }

    @Test
    @DisplayName("RegisterRequest の timezone にも同じ制約が載っている（入口を片方だけ塞がない）")
    void 登録DTOにも制約が載っている() throws NoSuchFieldException {
        assertThat(com.mannschaft.app.auth.dto.RegisterRequest.class
                .getDeclaredField("timezone")
                .isAnnotationPresent(ValidTimezone.class))
                .as("新規登録経路からも不正な timezone は入れられないこと")
                .isTrue();
    }

    /** timezone だけを差し替えた {@link UpdateProfileRequest} を作る（他項目は未指定＝null）。 */
    @Test
    void timezoneIsTrimmedBeforePersistence() {
        TeamEntity team = TeamEntity.builder().name("test").build();
        team.updateTimezone("  America/New_York  ");
        assertThat(team.getTimezone()).isEqualTo("America/New_York");
        assertThat(target.isValid(team.getTimezone(), null)).isTrue();
    }

    @Test
    void createPathNormalizesTimezoneBeforePersist() throws Exception {
        TeamEntity team = TeamEntity.builder().name("test").timezone("  America/New_York  ").build();
        var method = TeamEntity.class.getDeclaredMethod("normalizeTimezone");
        method.setAccessible(true);
        method.invoke(team);
        assertThat(team.getTimezone()).isEqualTo("America/New_York");
    }

    private static UpdateProfileRequest requestWithTimezone(String timezone) {
        return new UpdateProfileRequest(
                null, null, null, null, null, null, null, null,
                timezone, null, null, null, null, null);
    }
}
