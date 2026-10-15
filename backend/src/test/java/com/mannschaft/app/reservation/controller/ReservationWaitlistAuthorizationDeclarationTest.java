package com.mannschaft.app.reservation.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * キャンセル待ち（F03.4.5 §6.1）エンドポイントの認可宣言テスト（reflection・軽量）。
 *
 * <p>認可順序（§6.1 一意定義）を宣言レベルで固定する:
 * <ul>
 *   <li>件数取得（{@code count}）は ADMIN self-gate（{@code isScopeAdmin}）＝非 ADMIN は 403 が先。
 *       他チーム slot の 404 秘匿は Service 層（{@code findByIdAndTeamId}）が担い、403→404 の順になる。</li>
 *   <li>登録・取消・本人一覧は会員/公開フローのため {@code @PreAuthorize} を付けない
 *       （view ゲート・本人解決は Service 層）。</li>
 * </ul>
 * {@code @PreAuthorize} の実発火は共有の {@code @EnableMethodSecurity}（既存
 * {@code ReservationAuthorizationEnforcementTest} が同機構で 403 を実証）が担保する。</p>
 */
@DisplayName("キャンセル待ち 認可宣言テスト（F03.4.5 §6.1）")
class ReservationWaitlistAuthorizationDeclarationTest {

    private static final String ADMIN_GATE = "@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')";

    private static PreAuthorize annotationOf(Class<?> controller, String method, Class<?>... params) {
        try {
            return controller.getMethod(method, params).getAnnotation(PreAuthorize.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("メソッドが見つかりません: " + controller.getSimpleName() + "#" + method, e);
        }
    }

    @Test
    @DisplayName("件数取得(count)は isScopeAdmin ゲート（非 ADMIN は 403 が先）")
    void count_is_admin_gated() {
        PreAuthorize pa = annotationOf(ReservationWaitlistController.class, "count", Long.class, Long.class);
        assertThat(pa).as("count に @PreAuthorize が付与されていること").isNotNull();
        assertThat(pa.value()).isEqualTo(ADMIN_GATE);
    }

    @Test
    @DisplayName("登録・取消・本人一覧は会員/公開フローのためゲート無し")
    void member_flows_are_open() {
        assertThat(annotationOf(ReservationWaitlistController.class, "register", Long.class, Long.class))
                .as("register は view ゲート（Service 層）のため @PreAuthorize 無し").isNull();
        assertThat(annotationOf(ReservationWaitlistController.class, "cancel", Long.class, Long.class))
                .as("cancel は本人解決（Service 層）のため @PreAuthorize 無し").isNull();
        assertThat(annotationOf(MyReservationWaitlistController.class, "listMine"))
                .as("本人一覧は @PreAuthorize 無し（本人のみ返す）").isNull();
    }
}
