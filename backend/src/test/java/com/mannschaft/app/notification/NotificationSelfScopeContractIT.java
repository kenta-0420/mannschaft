package com.mannschaft.app.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.entity.NotificationPreferenceEntity;
import com.mannschaft.app.notification.entity.NotificationSettingsEntity;
import com.mannschaft.app.notification.entity.NotificationTypePreferenceEntity;
import com.mannschaft.app.notification.entity.PushSubscriptionEntity;
import com.mannschaft.app.notification.repository.NotificationPreferenceRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.repository.NotificationSettingsRepository;
import com.mannschaft.app.notification.repository.NotificationTypePreferenceRepository;
import com.mannschaft.app.notification.repository.PushSubscriptionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通知・通知設定・プッシュ購読の自己スコープエンドポイント 契約テスト（認可根治戦役 Wave4 ロットD）。
 *
 * <p>本テストは {@link com.mannschaft.app.notification.controller.NotificationController} /
 * {@link com.mannschaft.app.notification.controller.NotificationPreferenceController} /
 * {@link com.mannschaft.app.notification.controller.PushSubscriptionController} に付与した
 * {@code @SelfScopedEndpoint} の宣言（＝「検索・更新の対象が認証主体に束縛され、他人のデータへ
 * 構造的に到達できない」）を固定する。他ユーザーのデータを併存させたうえで、呼び出しユーザー自身の
 * 結果のみが返る／自身の行のみが変更されることを確認する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("通知・通知設定・プッシュ購読 自己スコープ契約テスト（認可根治 Wave4 ロットD）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationPreferenceRepository preferenceRepository;

    @Autowired
    private NotificationTypePreferenceRepository typePreferenceRepository;

    @Autowired
    private NotificationSettingsRepository settingsRepository;

    @Autowired
    private PushSubscriptionRepository pushSubscriptionRepository;

    @PersistenceContext
    private EntityManager em;

    /** 本テスト専用の固有ユーザーID（他 IT のフィクスチャと衝突しないレンジを使う）。 */
    private static final Long ME = 916401L;
    private static final Long OTHER = 916402L;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        for (Long userId : new Long[] {ME, OTHER}) {
            notificationRepository.deleteAll(
                    notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged()).getContent());
            preferenceRepository.deleteByUserId(userId);
            typePreferenceRepository.deleteByUserId(userId);
            settingsRepository.deleteByUserId(userId);
            pushSubscriptionRepository.deleteByUserId(userId);
        }
    }

    private NotificationEntity saveNotification(Long userId, String title) {
        // scope_type は NOT NULL（NotificationEntity.java:64-65）。特定チーム/組織に紐付かない
        // 個人宛通知のため NotificationScopeType.PERSONAL を用いる
        // （本番実装でも個人宛通知は同様に PERSONAL を使う。例: ContactRequestService.java:265）。
        return notificationRepository.saveAndFlush(NotificationEntity.builder()
                .userId(userId)
                .notificationType("SYSTEM_ANNOUNCEMENT")
                .title(title)
                .body("本文")
                .sourceType("SYSTEM")
                .sourceId(1L)
                .scopeType(NotificationScopeType.PERSONAL)
                .build());
    }

    // ═════════════════════════════════════════════════════════════════════
    // NotificationController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NotificationController#getUnreadCount / #markAllAsRead")
    class NotificationSelfScoped {

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("getUnreadCount は自分宛の未読件数のみを数える")
        void getUnreadCount_は自分の未読のみ数える() throws Exception {
            saveNotification(ME, "自分宛1");
            saveNotification(ME, "自分宛2");
            saveNotification(OTHER, "他人宛");

            // UnreadCountResponse のフィールド名は unreadCount（count ではない。
            // UnreadCountResponse.java: private final long unreadCount;）。
            mockMvc.perform(get("/api/v1/notifications/unread-count"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unreadCount").value(2));
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("markAllAsRead は自分宛の通知のみ既読化し、他ユーザーの通知は変化しない")
        void markAllAsRead_は自分の通知のみ既読化する() throws Exception {
            NotificationEntity mine = saveNotification(ME, "自分宛");
            NotificationEntity others = saveNotification(OTHER, "他人宛");

            mockMvc.perform(post("/api/v1/notifications/read-all"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value(1));

            // markAllAsReadByUserId は @Modifying の JPQL 一括 UPDATE
            // （NotificationRepository.java:49-51）であり、永続化コンテキスト上で既に管理されている
            // mine/others のインスタンスへは自動反映されない。flush で未確定の変更を確定させたうえで
            // clear して 1 次キャッシュを捨て、DB の実体を素通しで引き直す（flush 無しの clear は
            // 未確定の変更ごと捨てるため対で書く）。
            em.flush();
            em.clear();
            assertThat(notificationRepository.findById(mine.getId()).orElseThrow().getIsRead()).isTrue();
            assertThat(notificationRepository.findById(others.getId()).orElseThrow().getIsRead()).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // NotificationPreferenceController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("NotificationPreferenceController の自己スコープ EP 群")
    class PreferenceSelfScoped {

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("listPreferences は自分の設定のみを返す")
        void listPreferences_は自分の設定のみ返す() throws Exception {
            preferenceRepository.save(NotificationPreferenceEntity.builder()
                    .userId(ME).scopeType("TEAM").scopeId(1L).isEnabled(false).build());
            preferenceRepository.save(NotificationPreferenceEntity.builder()
                    .userId(OTHER).scopeType("TEAM").scopeId(1L).isEnabled(false).build());

            mockMvc.perform(get("/api/v1/notification-preferences"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("updatePreference は自分の行のみを作成・更新し、他ユーザーの行には触れない")
        void updatePreference_は自分の行のみ更新する() throws Exception {
            preferenceRepository.save(NotificationPreferenceEntity.builder()
                    .userId(OTHER).scopeType("TEAM").scopeId(9L).isEnabled(true).build());

            mockMvc.perform(put("/api/v1/notification-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"scopeType\":\"TEAM\",\"scopeId\":9,\"isEnabled\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.isEnabled").value(false));

            assertThat(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(ME, "TEAM", 9L))
                    .isPresent();
            assertThat(preferenceRepository.findByUserIdAndScopeTypeAndScopeId(OTHER, "TEAM", 9L)
                            .orElseThrow().getIsEnabled())
                    .isTrue();
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("listTypePreferences は自分の上書き設定のみを反映する")
        void listTypePreferences_は自分の上書きのみ反映する() throws Exception {
            typePreferenceRepository.save(NotificationTypePreferenceEntity.builder()
                    .userId(ME).notificationType("DAILY_DIGEST").isEnabled(true).build());
            typePreferenceRepository.save(NotificationTypePreferenceEntity.builder()
                    .userId(OTHER).notificationType("DAILY_DIGEST").isEnabled(false).build());

            mockMvc.perform(get("/api/v1/notification-type-preferences"))
                    .andExpect(status().isOk());

            assertThat(typePreferenceRepository.findByUserId(ME)).hasSize(1);
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("bulkUpdateTypePreferences は 1 件ずつ自分の行のみを対象にする")
        void bulkUpdateTypePreferences_は自分の行のみ更新する() throws Exception {
            // notificationType は NotificationType enum の name() と一致する必要がある
            // （NotificationPreferenceService#bulkUpdateTypePreferences が
            // NotificationType.fromValue で解決できない値を BusinessException として弾く）。
            // "MENTION" は存在せず "CHAT_MENTION" / "TIMELINE_MENTION" が正しい値
            // （NotificationType.java）。URGENT（isLocked）ではない CHAT_MENTION を使う。
            typePreferenceRepository.save(NotificationTypePreferenceEntity.builder()
                    .userId(OTHER).notificationType("CHAT_MENTION").isEnabled(true).build());

            mockMvc.perform(put("/api/v1/notification-type-preferences")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"preferences\":[{\"notificationType\":\"CHAT_MENTION\","
                                    + "\"isEnabled\":false,\"channelOverride\":false}]}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.updatedCount").value(1));

            assertThat(typePreferenceRepository.findByUserIdAndNotificationType(ME, "CHAT_MENTION")
                            .orElseThrow().getIsEnabled())
                    .isFalse();
            assertThat(typePreferenceRepository.findByUserIdAndNotificationType(OTHER, "CHAT_MENTION")
                            .orElseThrow().getIsEnabled())
                    .isTrue();
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("getSettings は自分のグローバル設定のみを返す")
        void getSettings_は自分の設定を返す() throws Exception {
            settingsRepository.save(NotificationSettingsEntity.builder()
                    .userId(OTHER).priorityAutoDelivery(false).build());

            mockMvc.perform(get("/api/v1/notification-settings"))
                    .andExpect(status().isOk())
                    // 自分の行が無いため既定値 true が返り、他ユーザーの false に影響されない。
                    .andExpect(jsonPath("$.data.priorityAutoDelivery").value(true));
        }

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("updateSettings は自分の行のみを更新し、他ユーザーの行には触れない")
        void updateSettings_は自分の行のみ更新する() throws Exception {
            settingsRepository.save(NotificationSettingsEntity.builder()
                    .userId(OTHER).priorityAutoDelivery(true).build());

            mockMvc.perform(put("/api/v1/notification-settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"priorityAutoDelivery\":false}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.priorityAutoDelivery").value(false));

            assertThat(settingsRepository.findByUserId(ME).orElseThrow().getPriorityAutoDelivery()).isFalse();
            assertThat(settingsRepository.findByUserId(OTHER).orElseThrow().getPriorityAutoDelivery()).isTrue();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PushSubscriptionController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PushSubscriptionController#subscribe")
    class PushSubscriptionSelfScoped {

        @Test
        @WithMockUser(username = "916401")
        @DisplayName("subscribe は呼び出しユーザーを購読の所有者として登録する")
        void subscribe_は本人所有で登録される() throws Exception {
            String endpoint = "https://push.example.com/w4c-lot-d-" + System.nanoTime();

            mockMvc.perform(post("/api/v1/push-subscriptions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "endpoint", endpoint,
                                    "p256dhKey", "p256dh-dummy",
                                    "authKey", "auth-dummy"))))
                    .andExpect(status().isCreated());

            PushSubscriptionEntity saved = pushSubscriptionRepository.findByEndpoint(endpoint).orElseThrow();
            assertThat(saved.getUserId()).isEqualTo(ME);
        }
    }
}
