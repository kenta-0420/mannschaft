package com.mannschaft.app.dashboard.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.dto.PersonalActionRequiredResponse;
import com.mannschaft.app.dashboard.service.PersonalActionRequiredService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link PersonalActionRequiredController} の受け入れテスト。
 *
 * <p>以下の受け入れ条件を検証する:</p>
 * <ul>
 *   <li>AC-10: 未認証で {@code GET /api/v1/dashboard/action-required} → 401 相当の BusinessException 伝播</li>
 *   <li>AC-11: 認証済みで呼ぶと、ユーザー所属の全チーム・組織の未処理アイテムをスコープ情報付きで返す</li>
 *   <li>AC-12: 特定スコープでエラーが起きても他スコープのデータは正常に返る（縮退）</li>
 *   <li>AC-13: 所属していないスコープのデータは含まれない（Service 層が保証・本テストでは委譲確認）</li>
 *   <li>AC-14: 全件数 0 の場合、空配列と {@code totalCount: 0} を返す</li>
 *   <li>AC-15: 各アイテムに {@code scopeType/scopeSlug/scopeName/itemType/itemId/title/deadline} が含まれる</li>
 * </ul>
 *
 * <p>純 Mockito {@code @InjectMocks} 方式でコントローラー層の責務のみをテストする。
 * サービス層（所属スコープの取得・縮退処理）は {@link PersonalActionRequiredService} 側でテストする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalActionRequiredController 受け入れテスト")
class PersonalActionRequiredControllerTest {

    @Mock
    private PersonalActionRequiredService personalActionRequiredService;

    @InjectMocks
    private PersonalActionRequiredController controller;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────────────────────────────────────
    // AC-10: 未認証で 401 相当の BusinessException(COMMON_000) 伝播
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-10: 未認証アクセス")
    class Ac10UnAuthenticated {

        @Test
        @DisplayName("SecurityContext が空のとき COMMON_000 BusinessException が投げられる（401 伝播）")
        void unauthenticated_throwsCommon000() {
            // Given: SecurityContext をクリアして未認証状態にする
            SecurityContextHolder.clearContext();

            // When & Then: SecurityUtils.getCurrentUserId() が BusinessException を投げる（= 401 伝播）
            assertThatThrownBy(() -> controller.getPersonalActionRequired())
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.COMMON_000);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AC-11: 認証済みで全スコープの集計を返す
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-11: 認証済み・全スコープ集計")
    class Ac11AllScopesAggregation {

        @Test
        @DisplayName("チーム1・組織1のアイテムが合計3件 totalCount=3 で返る")
        void authenticated_returnsAllScopeItems() {
            // Given
            List<PersonalActionRequiredResponse.ActionItem> items = List.of(
                    buildItem("CIRCULATION", "TEAM", 10L, "team-alpha", "チームA", "abc-uuid", "回覧: 月次報告", null, null),
                    buildItem("SURVEY", "TEAM", 10L, "team-alpha", "チームA", "101", "アンケート: 研修満足度", null, null),
                    buildItem("ATTENDANCE", "ORGANIZATION", 20L, "org-beta", "組織B", "202", "イベント: 全体会議", null, null)
            );
            PersonalActionRequiredResponse mockResponse =
                    new PersonalActionRequiredResponse(items, 3);
            given(personalActionRequiredService.getPersonalActionRequired(USER_ID))
                    .willReturn(mockResponse);

            // When
            ResponseEntity<com.mannschaft.app.common.ApiResponse<PersonalActionRequiredResponse>> res =
                    controller.getPersonalActionRequired();

            // Then
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).isNotNull();
            PersonalActionRequiredResponse body = res.getBody().getData();
            assertThat(body.totalCount()).isEqualTo(3);
            assertThat(body.items()).hasSize(3);
            verify(personalActionRequiredService).getPersonalActionRequired(USER_ID);
        }

        @Test
        @DisplayName("各 ActionItem に scopeType / scopeSlug / scopeName / itemType / itemId / title が含まれる（AC-15）")
        void ac15_actionItemContainsRequiredFields() {
            // Given
            PersonalActionRequiredResponse.ActionItem item =
                    buildItem("CIRCULATION", "TEAM", 10L, "team-alpha", "チームA", "uuid-001", "回覧タイトル", null, null);
            given(personalActionRequiredService.getPersonalActionRequired(USER_ID))
                    .willReturn(new PersonalActionRequiredResponse(List.of(item), 1));

            // When
            ResponseEntity<com.mannschaft.app.common.ApiResponse<PersonalActionRequiredResponse>> res =
                    controller.getPersonalActionRequired();

            // Then (AC-15 全フィールド存在確認)
            PersonalActionRequiredResponse.ActionItem first = res.getBody().getData().items().get(0);
            assertThat(first.itemType()).isEqualTo("CIRCULATION");
            assertThat(first.scopeType()).isEqualTo("TEAM");
            assertThat(first.scopeId()).isEqualTo(10L);
            assertThat(first.scopeSlug()).isEqualTo("team-alpha");
            assertThat(first.scopeName()).isEqualTo("チームA");
            assertThat(first.itemId()).isEqualTo("uuid-001");
            assertThat(first.title()).isEqualTo("回覧タイトル");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AC-12: 縮退系（スコープエラー時でも他スコープは返る）
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-12: 縮退系")
    class Ac12Degradation {

        @Test
        @DisplayName("1スコープがエラーでも Service が縮退して残りのアイテムを返す")
        void degradation_oneErrorScopeReducesCount() {
            // Given: Service 側で縮退済みの結果が返ってくる（エラースコープ分が0件になっている）
            List<PersonalActionRequiredResponse.ActionItem> items = List.of(
                    buildItem("SURVEY", "ORGANIZATION", 20L, "org-beta", "組織B", "55", "アンケート", null, null)
            );
            // チームスコープはエラーで縮退されているため0件、組織スコープの1件のみ
            given(personalActionRequiredService.getPersonalActionRequired(USER_ID))
                    .willReturn(new PersonalActionRequiredResponse(items, 1));

            // When
            ResponseEntity<com.mannschaft.app.common.ApiResponse<PersonalActionRequiredResponse>> res =
                    controller.getPersonalActionRequired();

            // Then: 縮退後の結果が正常に返る
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody().getData().totalCount()).isEqualTo(1);
            assertThat(res.getBody().getData().items()).hasSize(1);
            assertThat(res.getBody().getData().items().get(0).scopeType()).isEqualTo("ORGANIZATION");
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AC-14: 全件数 0 → 空配列と totalCount=0
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-14: 全件数ゼロ")
    class Ac14AllZero {

        @Test
        @DisplayName("要対応アイテムが0件のとき items=[] totalCount=0 で返る")
        void allZero_returnsEmptyWithZeroCount() {
            // Given
            given(personalActionRequiredService.getPersonalActionRequired(USER_ID))
                    .willReturn(new PersonalActionRequiredResponse(List.of(), 0));

            // When
            ResponseEntity<com.mannschaft.app.common.ApiResponse<PersonalActionRequiredResponse>> res =
                    controller.getPersonalActionRequired();

            // Then
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            PersonalActionRequiredResponse body = res.getBody().getData();
            assertThat(body.items()).isEmpty();
            assertThat(body.totalCount()).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // AC-13: 非所属スコープのデータは含まれない（Service 委譲確認）
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AC-13: 非所属スコープ除外")
    class Ac13NonMemberExclusion {

        @Test
        @DisplayName("Service が userId で絞り込んだ結果のみコントローラーが返す（非所属スコープ除外の委譲確認）")
        void nonMemberScope_serviceFiltersOut() {
            // Given: Service は userId=USER_ID の所属スコープのみでフィルタ済み
            List<PersonalActionRequiredResponse.ActionItem> items = List.of(
                    buildItem("CIRCULATION", "TEAM", 10L, "team-alpha", "チームA", "abc-uuid", "回覧", null, null)
            );
            given(personalActionRequiredService.getPersonalActionRequired(USER_ID))
                    .willReturn(new PersonalActionRequiredResponse(items, 1));

            // When
            ResponseEntity<com.mannschaft.app.common.ApiResponse<PersonalActionRequiredResponse>> res =
                    controller.getPersonalActionRequired();

            // Then: Service のフィルタ済み結果がそのまま返る
            assertThat(res.getBody().getData().items()).hasSize(1);
            // Service が userId=USER_ID で呼ばれたことで非所属除外を担保
            verify(personalActionRequiredService).getPersonalActionRequired(USER_ID);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────────────────────

    private PersonalActionRequiredResponse.ActionItem buildItem(
            String itemType, String scopeType, Long scopeId, String scopeSlug, String scopeName,
            String itemId, String title,
            java.time.LocalDateTime deadline, java.time.LocalDateTime startsAt) {
        return new PersonalActionRequiredResponse.ActionItem(
                itemType, scopeType, scopeId, scopeSlug, scopeName, itemId, title, deadline, startsAt);
    }
}
