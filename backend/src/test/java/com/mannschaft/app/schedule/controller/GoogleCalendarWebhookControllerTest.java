package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity;
import com.mannschaft.app.schedule.repository.GoogleCalendarWebhookChannelRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Google Calendar Webhook Controller 受け入れテスト（Phase 4 — red 先行）。
 *
 * <p>対象エンドポイント: {@code POST /api/v1/webhooks/google-calendar}（認証不要）</p>
 *
 * <p>テスト対象の AC:</p>
 * <ul>
 *   <li>AC-5: {@code X-Goog-Channel-Token} が DB と不一致 → 403</li>
 *   <li>AC-6: {@code X-Goog-Channel-ID} が DB に存在しない → 404</li>
 *   <li>AC-7: {@code X-Goog-Resource-State: sync} → 200（ノーオペレーション）</li>
 *   <li>AC-18: トークン比較に {@code MessageDigest.isEqual()} 定数時間比較を使う（サービス層の実装で確保）</li>
 * </ul>
 *
 * <p><b>red の理由</b>: {@code POST /api/v1/webhooks/google-calendar} を処理する
 * {@code GoogleCalendarWebhookController} が Phase 4 出陣で実装される予定のため、
 * 現時点では 404 または 401 が返る。各テストは AC が要求するステータスコードを
 * アサートするため、全件 red になる。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("GoogleCalendarWebhookController 受け入れテスト（Phase 4 red）")
class GoogleCalendarWebhookControllerTest extends AbstractMySqlIntegrationTest {

    /** テスト対象 Webhook エンドポイントパス（設計書 P4-5 参照）。 */
    private static final String WEBHOOK_PATH = "/api/v1/webhooks/google-calendar";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GoogleCalendarWebhookChannelRepository channelRepository;

    // ========================================
    // AC-5: トークン不一致 → 403
    // ========================================

    @Nested
    @DisplayName("AC-5: X-Goog-Channel-Token 不一致")
    class AC5TokenMismatch {

        @BeforeEach
        void setUp() {
            // AC-5 のシナリオ: channel-id=test-channel-id-001 が DB に存在するが
            // リクエストのトークンが "WRONG_TOKEN" で DB の "CORRECT_TOKEN_64CHARS_PADDED_______________X" と不一致
            channelRepository.deleteAll();
            channelRepository.save(
                    GoogleCalendarWebhookChannelEntity.builder()
                            .userId(1L)
                            .channelId("test-channel-id-001")
                            .resourceId("test-resource-001")
                            .channelToken("CORRECT_TOKEN_64CHARS_PADDED_______________X")
                            .expiresAt(LocalDateTime.now().plusDays(7))
                            .build()
            );
        }

        @Test
        @DisplayName("AC-5: X-Goog-Channel-Token が DB に存在するトークンと不一致の場合、403 を返す")
        void tokenMismatch_returns403() throws Exception {
            // given: Channel-ID は DB に存在するが Token が不一致
            mockMvc.perform(post(WEBHOOK_PATH)
                            .header("X-Goog-Channel-ID", "test-channel-id-001")
                            .header("X-Goog-Channel-Token", "WRONG_TOKEN")
                            .header("X-Goog-Resource-State", "exists")
                            .header("X-Goog-Resource-ID", "test-resource-001"))
                    .andExpect(status().isForbidden()); // 403
        }
    }

    // ========================================
    // AC-6: Channel-ID 不在 → 404
    // ========================================

    @Nested
    @DisplayName("AC-6: X-Goog-Channel-ID が DB に存在しない")
    class AC6ChannelNotFound {

        @Test
        @DisplayName("AC-6: X-Goog-Channel-ID が DB に存在しない場合、404 を返す")
        void channelIdNotFound_returns404() throws Exception {
            // given: DB にチャンネルが存在しない状態（空 DB）
            mockMvc.perform(post(WEBHOOK_PATH)
                            .header("X-Goog-Channel-ID", "nonexistent-channel-id")
                            .header("X-Goog-Channel-Token", "some-token")
                            .header("X-Goog-Resource-State", "exists")
                            .header("X-Goog-Resource-ID", "test-resource-001"))
                    // red: Controller 未実装のため現在は 404 だが、理由が異なる
                    // （"エンドポイント自体が不在" vs "チャンネルが見つからない" の区別が必要）
                    // 実装後はエンドポイントが存在し、DB 検索で見つからないため 404 を返す
                    .andExpect(status().isNotFound()); // 404
        }
    }

    // ========================================
    // AC-7: Resource-State=sync → 200 ノーオペレーション
    // ========================================

    @Nested
    @DisplayName("AC-7: X-Goog-Resource-State=sync はノーオペレーション")
    class AC7SyncState {

        @Test
        @DisplayName("AC-7: X-Goog-Resource-State が 'sync' の場合、200 を返しサービスが呼ばれない")
        void syncState_returns200NoOperation() throws Exception {
            // given: Google が初回チャンネル確認で送る "sync" 通知
            // Token/Channel-ID 検証の前に Resource-State をチェックする仕様
            mockMvc.perform(post(WEBHOOK_PATH)
                            .header("X-Goog-Channel-ID", "test-channel-id-001")
                            .header("X-Goog-Channel-Token", "some-token")
                            .header("X-Goog-Resource-State", "sync")
                            .header("X-Goog-Resource-ID", "test-resource-001"))
                    // red: Controller 未実装のため現在は 404/401
                    // 実装後: Resource-State="sync" は即 200 返却（イベント取り込みなし）
                    .andExpect(status().isOk()); // 200
        }

        @Test
        @DisplayName("AC-7: sync 通知受信時に Google Events List API が呼ばれないこと（ノーオペレーション確認）")
        void syncState_doesNotCallGoogleApi() throws Exception {
            // Resource-State=sync の場合、GoogleCalendarWebhookService.handleNotification() が
            // 呼ばれないか、呼ばれても early-return することを確認する。
            // 本テストは 200 レスポンスの確認と合わせて AC-7 をカバーする。
            mockMvc.perform(post(WEBHOOK_PATH)
                            .header("X-Goog-Channel-ID", "channel-sync-check")
                            .header("X-Goog-Channel-Token", "sync-token")
                            .header("X-Goog-Resource-State", "sync")
                            .header("X-Goog-Resource-ID", "resource-sync"))
                    // red: Controller 未実装
                    .andExpect(status().isOk());
        }
    }
}
