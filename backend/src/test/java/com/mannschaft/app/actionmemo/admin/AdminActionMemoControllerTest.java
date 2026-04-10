package com.mannschaft.app.actionmemo.admin;

import com.mannschaft.app.actionmemo.admin.dto.RegenerateWeeklySummaryResponse;
import com.mannschaft.app.actionmemo.service.ActionMemoWeeklySummaryService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AdminActionMemoController} 単体テスト。
 *
 * <p>設計書 §5.5 / Phase 3 タスク B に従い以下を検証する:</p>
 * <ul>
 *   <li>{@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} がクラスレベルで付与されていること
 *       （Method Security 有効化後に実機で 403 判定が効く）</li>
 *   <li>{@code userId} 指定時は対象1ユーザーのみ再生成 Service が呼ばれる</li>
 *   <li>{@code userId} 省略時は全ユーザー再生成 Service が呼ばれる</li>
 * </ul>
 *
 * <p><b>認可テストの実装方針</b>: 現時点の {@code SecurityConfig} は
 * {@code @EnableMethodSecurity} を有効化しておらず（開発中は全エンドポイント素通し）、
 * MockMvc で 403 を直接検証することが難しい。そのため本テストでは
 * {@code @PreAuthorize} アノテーションの文字列存在を Reflection で assert することで
 * 「認可が定義されていること」を担保する。将来 {@code EnableMethodSecurity} が
 * 有効化された時点で Spring Security が自動的に 403 を返すようになる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminActionMemoController 単体テスト")
class AdminActionMemoControllerTest {

    @Mock
    private ActionMemoWeeklySummaryService weeklySummaryService;

    @InjectMocks
    private AdminActionMemoController controller;

    private static final Long ADMIN_USER_ID = 1L;
    private static final Long TARGET_USER_ID = 123L;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    /** Service を呼ぶテストで共通利用する期間スタブ設定 */
    private void stubCurrentPeriod() {
        LocalDate from = LocalDate.of(2026, 4, 2);
        LocalDate to = LocalDate.of(2026, 4, 8);
        given(weeklySummaryService.currentPeriod())
                .willReturn(new LocalDate[]{from, to});
    }

    @BeforeEach
    void setUp() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(ADMIN_USER_ID);
    }

    @AfterEach
    void tearDown() {
        if (securityUtilsMock != null) {
            securityUtilsMock.close();
        }
    }

    // ==================================================================
    // SYSTEM_ADMIN のみアクセス可（@PreAuthorize 存在確認）
    // ==================================================================

    @Nested
    @DisplayName("@PreAuthorize('hasRole(SYSTEM_ADMIN)') がクラスレベルで付与されている")
    class AuthorizationTest {

        @Test
        @DisplayName("クラスに @PreAuthorize('hasRole(SYSTEM_ADMIN)') アノテーションが存在する")
        void classLevelPreAuthorizeDeclaresSystemAdminRole() {
            PreAuthorize annotation = AdminActionMemoController.class
                    .getAnnotation(PreAuthorize.class);

            assertThat(annotation)
                    .as("クラスレベル @PreAuthorize が未付与だと全ユーザーが管理 API を叩けてしまう")
                    .isNotNull();
            assertThat(annotation.value())
                    .as("SYSTEM_ADMIN ロール以外を拒否する式でなければならない")
                    .isEqualTo("hasRole('SYSTEM_ADMIN')");
        }
    }

    // ==================================================================
    // userId 指定時 = 1 ユーザーのみ再生成
    // ==================================================================

    @Nested
    @DisplayName("regenerate: userId 指定")
    class RegenerateSingleUserTest {

        @Test
        @DisplayName("userId 指定時は regenerateForUser が 1 回だけ呼ばれる")
        void regenerate_withUserId_callsRegenerateForUserOnce() {
            stubCurrentPeriod();
            given(weeklySummaryService.regenerateForUser(eq(TARGET_USER_ID), any(), any()))
                    .willReturn(true);

            ResponseEntity<ApiResponse<RegenerateWeeklySummaryResponse>> response =
                    controller.regenerateWeeklySummary(TARGET_USER_ID);

            RegenerateWeeklySummaryResponse body = response.getBody().getData();
            assertThat(body.getRegeneratedCount()).isEqualTo(1);
            assertThat(body.getSkippedCount()).isEqualTo(0);
            assertThat(body.getFailedCount()).isEqualTo(0);

            verify(weeklySummaryService, times(1))
                    .regenerateForUser(eq(TARGET_USER_ID), any(), any());
            verify(weeklySummaryService, never()).regenerateForAll(any(), any());
        }

        @Test
        @DisplayName("userId 指定時に対象ユーザーが 0件メモなら skipped_count=1")
        void regenerate_withUserId_zeroMemo_returnsSkipped() {
            stubCurrentPeriod();
            given(weeklySummaryService.regenerateForUser(eq(TARGET_USER_ID), any(), any()))
                    .willReturn(false);

            ResponseEntity<ApiResponse<RegenerateWeeklySummaryResponse>> response =
                    controller.regenerateWeeklySummary(TARGET_USER_ID);

            RegenerateWeeklySummaryResponse body = response.getBody().getData();
            assertThat(body.getRegeneratedCount()).isEqualTo(0);
            assertThat(body.getSkippedCount()).isEqualTo(1);
            assertThat(body.getFailedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("userId 指定時に Service が例外を投げたら failed_count=1（次の処理に進まない）")
        void regenerate_withUserId_exception_returnsFailed() {
            stubCurrentPeriod();
            given(weeklySummaryService.regenerateForUser(eq(TARGET_USER_ID), any(), any()))
                    .willThrow(new RuntimeException("DB エラー（模擬）"));

            ResponseEntity<ApiResponse<RegenerateWeeklySummaryResponse>> response =
                    controller.regenerateWeeklySummary(TARGET_USER_ID);

            RegenerateWeeklySummaryResponse body = response.getBody().getData();
            assertThat(body.getRegeneratedCount()).isEqualTo(0);
            assertThat(body.getSkippedCount()).isEqualTo(0);
            assertThat(body.getFailedCount()).isEqualTo(1);
        }
    }

    // ==================================================================
    // userId 省略時 = 全ユーザー再生成
    // ==================================================================

    @Nested
    @DisplayName("regenerate: userId 省略")
    class RegenerateAllUsersTest {

        @Test
        @DisplayName("userId 省略時は regenerateForAll が呼ばれ、結果件数がそのまま返る")
        void regenerate_withoutUserId_callsRegenerateForAll() {
            stubCurrentPeriod();
            given(weeklySummaryService.regenerateForAll(any(), any()))
                    .willReturn(new ActionMemoWeeklySummaryService.RegenerationResult(3, 1, 0));

            ResponseEntity<ApiResponse<RegenerateWeeklySummaryResponse>> response =
                    controller.regenerateWeeklySummary(null);

            RegenerateWeeklySummaryResponse body = response.getBody().getData();
            assertThat(body.getRegeneratedCount()).isEqualTo(3);
            assertThat(body.getSkippedCount()).isEqualTo(1);
            assertThat(body.getFailedCount()).isEqualTo(0);

            verify(weeklySummaryService, times(1)).regenerateForAll(any(), any());
            verify(weeklySummaryService, never()).regenerateForUser(anyLong(), any(), any());
        }
    }
}
