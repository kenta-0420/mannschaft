package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.UpdateRecurringBlockedTimeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定期予約不可枠（F03.4.5 §4 W2-2）エンドポイントの認可宣言テスト（reflection・軽量）。
 *
 * <p>受け入れ条件 R-11: 全 5 API（一覧/作成/更新/削除/impact）が同一の ADMIN self-gate
 * （{@code @accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')}）を持つことを固定する。
 * {@code @PreAuthorize} の実発火（403/401）は共有の {@code @EnableMethodSecurity}
 * （既存 {@code ReservationAuthorizationEnforcementTest} が同機構で実証）が担保する。</p>
 */
@DisplayName("定期予約不可枠 認可宣言テスト（F03.4.5 §4 W2-2・AC R-11）")
class ReservationRecurringBlockedTimeAuthorizationDeclarationTest {

    private static final String ADMIN_GATE = "@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')";

    private static PreAuthorize annotationOf(String method, Class<?>... params) {
        try {
            return ReservationRecurringBlockedTimeController.class.getMethod(method, params)
                    .getAnnotation(PreAuthorize.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "メソッドが見つかりません: " + ReservationRecurringBlockedTimeController.class.getSimpleName()
                            + "#" + method, e);
        }
    }

    @Test
    @DisplayName("R-11: listRules は isScopeAdmin ゲート")
    void listRules_is_admin_gated() {
        PreAuthorize pa = annotationOf("listRules", Long.class);
        assertThat(pa).as("listRules に @PreAuthorize が付与されていること").isNotNull();
        assertThat(pa.value()).isEqualTo(ADMIN_GATE);
    }

    @Test
    @DisplayName("R-11: createRule は isScopeAdmin ゲート")
    void createRule_is_admin_gated() {
        PreAuthorize pa = annotationOf("createRule", Long.class, CreateRecurringBlockedTimeRequest.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).isEqualTo(ADMIN_GATE);
    }

    @Test
    @DisplayName("R-11: updateRule は isScopeAdmin ゲート")
    void updateRule_is_admin_gated() {
        PreAuthorize pa = annotationOf(
                "updateRule", Long.class, UUID.class, UpdateRecurringBlockedTimeRequest.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).isEqualTo(ADMIN_GATE);
    }

    @Test
    @DisplayName("R-11: deleteRule は isScopeAdmin ゲート")
    void deleteRule_is_admin_gated() {
        PreAuthorize pa = annotationOf("deleteRule", Long.class, UUID.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).isEqualTo(ADMIN_GATE);
    }

    @Test
    @DisplayName("R-11: getImpact は isScopeAdmin ゲート")
    void getImpact_is_admin_gated() {
        PreAuthorize pa = annotationOf(
                "getImpact", Long.class, ReservationDayOfWeek.class, LocalTime.class, LocalTime.class, Long.class);
        assertThat(pa).isNotNull();
        assertThat(pa.value()).isEqualTo(ADMIN_GATE);
    }
}
