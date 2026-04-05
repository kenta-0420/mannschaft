package com.mannschaft.app.line;

import com.mannschaft.app.line.dto.LineBotConfigResponse;
import com.mannschaft.app.line.dto.LineMessageLogResponse;
import com.mannschaft.app.line.dto.SnsFeedConfigResponse;
import com.mannschaft.app.line.dto.UserLineStatusResponse;
import com.mannschaft.app.line.entity.LineBotConfigEntity;
import com.mannschaft.app.line.entity.LineMessageLogEntity;
import com.mannschaft.app.line.entity.SnsFeedConfigEntity;
import com.mannschaft.app.line.entity.UserLineConnectionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LineMapper} の単体テスト。
 * MapStructImpl を直接インスタンス化してマッピングを検証する。
 */
@DisplayName("LineMapper 単体テスト")
class LineMapperTest {

    private LineMapperImpl mapper;

    @BeforeEach
    void setUp() {
        mapper = new LineMapperImpl();
    }

    private void setId(Object entity, Long id) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }

    private void setCreatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("createdAt");
        f.setAccessible(true);
        f.set(entity, dt);
    }

    private void setUpdatedAt(Object entity, LocalDateTime dt) throws Exception {
        Field f = entity.getClass().getSuperclass().getDeclaredField("updatedAt");
        f.setAccessible(true);
        f.set(entity, dt);
    }

    // ── toLineBotConfigResponse ──

    @Nested
    @DisplayName("toLineBotConfigResponse")
    class ToLineBotConfigResponse {

        @Test
        @DisplayName("LINE BOT設定エンティティからレスポンスに変換できる")
        void LINE_BOT設定エンティティからレスポンスに変換できる() throws Exception {
            LocalDateTime now = LocalDateTime.of(2026, 1, 1, 10, 0);
            LineBotConfigEntity entity = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(1L)
                    .channelId("channel-001")
                    .channelSecretEnc(new byte[]{1, 2})
                    .channelAccessTokenEnc(new byte[]{3, 4})
                    .webhookSecret("webhook-secret")
                    .botUserId("bot-user-001")
                    .isActive(true)
                    .notificationEnabled(true)
                    .configuredBy(10L)
                    .build();
            setId(entity, 100L);
            setCreatedAt(entity, now);
            setUpdatedAt(entity, now);

            LineBotConfigResponse result = mapper.toLineBotConfigResponse(entity);

            assertThat(result.getId()).isEqualTo(100L);
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getScopeId()).isEqualTo(1L);
            assertThat(result.getChannelId()).isEqualTo("channel-001");
            assertThat(result.getWebhookSecret()).isEqualTo("webhook-secret");
            assertThat(result.getBotUserId()).isEqualTo("bot-user-001");
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getNotificationEnabled()).isTrue();
            assertThat(result.getConfiguredBy()).isEqualTo(10L);
            assertThat(result.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("scopeTypeがORGANIZATIONの場合も正常に変換できる")
        void scopeTypeがORGANIZATIONの場合も正常に変換できる() throws Exception {
            LineBotConfigEntity entity = LineBotConfigEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(2L)
                    .channelId("ch-org")
                    .channelSecretEnc(new byte[]{})
                    .channelAccessTokenEnc(new byte[]{})
                    .webhookSecret("ws")
                    .notificationEnabled(false)
                    .configuredBy(1L)
                    .build();
            setId(entity, 101L);

            LineBotConfigResponse result = mapper.toLineBotConfigResponse(entity);

            assertThat(result.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(result.getNotificationEnabled()).isFalse();
        }

        @Test
        @DisplayName("null エンティティは null を返す")
        void null_エンティティは_null_を返す() {
            assertThat(mapper.toLineBotConfigResponse(null)).isNull();
        }
    }

    // ── toLineMessageLogResponse ──

    @Nested
    @DisplayName("toLineMessageLogResponse")
    class ToLineMessageLogResponse {

        @Test
        @DisplayName("OUTBOUNDメッセージログエンティティからレスポンスに変換できる")
        void OUTBOUNDメッセージログエンティティからレスポンスに変換できる() throws Exception {
            LocalDateTime now = LocalDateTime.of(2026, 2, 1, 9, 0);
            LineMessageLogEntity entity = LineMessageLogEntity.builder()
                    .lineBotConfigId(100L)
                    .direction(MessageDirection.OUTBOUND)
                    .messageType(LineMessageType.TEXT)
                    .lineUserId("line-user-001")
                    .contentSummary("こんにちは")
                    .lineMessageId("msg-001")
                    .status(MessageStatus.SENT)
                    .build();
            setId(entity, 200L);
            setCreatedAt(entity, now);

            LineMessageLogResponse result = mapper.toLineMessageLogResponse(entity);

            assertThat(result.getId()).isEqualTo(200L);
            assertThat(result.getLineBotConfigId()).isEqualTo(100L);
            assertThat(result.getDirection()).isEqualTo("OUTBOUND");
            assertThat(result.getMessageType()).isEqualTo("TEXT");
            assertThat(result.getLineUserId()).isEqualTo("line-user-001");
            assertThat(result.getContentSummary()).isEqualTo("こんにちは");
            assertThat(result.getLineMessageId()).isEqualTo("msg-001");
            assertThat(result.getStatus()).isEqualTo("SENT");
            assertThat(result.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("INBOUNDメッセージログエンティティのdirectionがINBOUNDになる")
        void INBOUNDメッセージログエンティティのdirectionがINBOUNDになる() throws Exception {
            LineMessageLogEntity entity = LineMessageLogEntity.builder()
                    .lineBotConfigId(100L)
                    .direction(MessageDirection.INBOUND)
                    .messageType(LineMessageType.WEBHOOK_EVENT)
                    .status(MessageStatus.PENDING)
                    .build();
            setId(entity, 201L);

            LineMessageLogResponse result = mapper.toLineMessageLogResponse(entity);

            assertThat(result.getDirection()).isEqualTo("INBOUND");
            assertThat(result.getMessageType()).isEqualTo("WEBHOOK_EVENT");
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("FAILEDステータスのメッセージログが変換できる")
        void FAILEDステータスのメッセージログが変換できる() throws Exception {
            LineMessageLogEntity entity = LineMessageLogEntity.builder()
                    .lineBotConfigId(100L)
                    .direction(MessageDirection.OUTBOUND)
                    .messageType(LineMessageType.FLEX)
                    .status(MessageStatus.FAILED)
                    .errorDetail("Network error")
                    .build();
            setId(entity, 202L);

            LineMessageLogResponse result = mapper.toLineMessageLogResponse(entity);

            assertThat(result.getStatus()).isEqualTo("FAILED");
            assertThat(result.getErrorDetail()).isEqualTo("Network error");
        }

        @Test
        @DisplayName("null エンティティは null を返す")
        void null_エンティティは_null_を返す() {
            assertThat(mapper.toLineMessageLogResponse(null)).isNull();
        }
    }

    // ── toUserLineStatusResponse ──

    @Nested
    @DisplayName("toUserLineStatusResponse")
    class ToUserLineStatusResponse {

        @Test
        @DisplayName("ユーザーLINE連携エンティティからレスポンスに変換できる")
        void ユーザーLINE連携エンティティからレスポンスに変換できる() throws Exception {
            LocalDateTime linkedAt = LocalDateTime.of(2026, 1, 15, 12, 0);
            UserLineConnectionEntity entity = UserLineConnectionEntity.builder()
                    .userId(10L)
                    .lineUserId("LINE_USER_001")
                    .displayName("山田太郎")
                    .pictureUrl("https://example.com/pic.jpg")
                    .statusMessage("よろしく！")
                    .isActive(true)
                    .linkedAt(linkedAt)
                    .build();
            setId(entity, 300L);

            UserLineStatusResponse result = mapper.toUserLineStatusResponse(entity);

            // isLinked は constant = "true"
            assertThat(result.getIsLinked()).isTrue();
            assertThat(result.getLineUserId()).isEqualTo("LINE_USER_001");
            assertThat(result.getDisplayName()).isEqualTo("山田太郎");
            assertThat(result.getPictureUrl()).isEqualTo("https://example.com/pic.jpg");
            assertThat(result.getStatusMessage()).isEqualTo("よろしく！");
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getLinkedAt()).isEqualTo(linkedAt);
        }

        @Test
        @DisplayName("非アクティブな連携エンティティも変換できる")
        void 非アクティブな連携エンティティも変換できる() throws Exception {
            UserLineConnectionEntity entity = UserLineConnectionEntity.builder()
                    .userId(20L)
                    .lineUserId("LINE_USER_002")
                    .isActive(false)
                    .linkedAt(LocalDateTime.now())
                    .build();
            setId(entity, 301L);
            entity.deactivate();

            UserLineStatusResponse result = mapper.toUserLineStatusResponse(entity);

            // isLinked は constant = "true" （連携レコードが存在すれば常にtrue）
            assertThat(result.getIsLinked()).isTrue();
            assertThat(result.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("null エンティティは null を返す")
        void null_エンティティは_null_を返す() {
            assertThat(mapper.toUserLineStatusResponse(null)).isNull();
        }
    }

    // ── toSnsFeedConfigResponse ──

    @Nested
    @DisplayName("toSnsFeedConfigResponse")
    class ToSnsFeedConfigResponse {

        @Test
        @DisplayName("SNSフィード設定エンティティからレスポンスに変換できる")
        void SNSフィード設定エンティティからレスポンスに変換できる() throws Exception {
            LocalDateTime now = LocalDateTime.of(2026, 3, 1, 10, 0);
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(1L)
                    .provider(SnsProvider.INSTAGRAM)
                    .accountUsername("my_team_ig")
                    .displayCount((short) 9)
                    .isActive(true)
                    .configuredBy(10L)
                    .build();
            setId(entity, 400L);
            setCreatedAt(entity, now);
            setUpdatedAt(entity, now);

            SnsFeedConfigResponse result = mapper.toSnsFeedConfigResponse(entity);

            assertThat(result.getId()).isEqualTo(400L);
            assertThat(result.getScopeType()).isEqualTo("TEAM");
            assertThat(result.getScopeId()).isEqualTo(1L);
            assertThat(result.getProvider()).isEqualTo("INSTAGRAM");
            assertThat(result.getAccountUsername()).isEqualTo("my_team_ig");
            assertThat(result.getDisplayCount()).isEqualTo((short) 9);
            assertThat(result.getIsActive()).isTrue();
            assertThat(result.getConfiguredBy()).isEqualTo(10L);
            assertThat(result.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("scopeTypeがORGANIZATIONでも正常に変換できる")
        void scopeTypeがORGANIZATIONでも正常に変換できる() throws Exception {
            SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(5L)
                    .provider(SnsProvider.INSTAGRAM)
                    .accountUsername("org_ig")
                    .displayCount((short) 6)
                    .isActive(false)
                    .configuredBy(1L)
                    .build();
            setId(entity, 401L);

            SnsFeedConfigResponse result = mapper.toSnsFeedConfigResponse(entity);

            assertThat(result.getScopeType()).isEqualTo("ORGANIZATION");
            assertThat(result.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("null エンティティは null を返す")
        void null_エンティティは_null_を返す() {
            assertThat(mapper.toSnsFeedConfigResponse(null)).isNull();
        }
    }
}
