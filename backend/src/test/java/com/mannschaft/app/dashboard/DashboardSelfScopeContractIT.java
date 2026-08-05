package com.mannschaft.app.dashboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
import com.mannschaft.app.dashboard.repository.DashboardWidgetSettingRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 個人ダッシュボード・チャットフォルダの自己スコープエンドポイント 契約テスト（認可根治戦役 Wave4 ロットD）。
 *
 * <p>本テストは {@link com.mannschaft.app.dashboard.controller.DashboardController} /
 * {@link com.mannschaft.app.dashboard.controller.ChatFolderController} に付与した
 * {@code @SelfScopedEndpoint} の宣言を固定する。ID を一切受け取らない参照系は「他ユーザーのデータが
 * 混入しないこと」、{@code resetWidgetSettings} は「呼び出しユーザー自身の設定行しか変更されないこと」を
 * それぞれ実 DB で確認する。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("個人ダッシュボード・チャットフォルダ 自己スコープ契約テスト（認可根治 Wave4 ロットD）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DashboardSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private ChatContactFolderRepository folderRepository;

    @Autowired
    private DashboardWidgetSettingRepository widgetSettingRepository;

    /** 本テスト専用の固有ユーザーID（他 IT のフィクスチャと衝突しないレンジを使う）。 */
    private static final Long ME = 916501L;
    private static final Long OTHER = 916502L;

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
            folderRepository.deleteAll(folderRepository.findByUserIdOrderBySortOrder(userId));
            // クラス @Transactional 配下（@BeforeEach/@AfterEach も同一トランザクションに含まれる）
            // なので @Modifying クエリ・derived delete のどちらでも安全に呼べる。
            // find → deleteAll の形にしているのは他の契約 IT（NotificationSelfScopeContractIT 等）と
            // 削除手段を揃えるため。
            widgetSettingRepository.deleteAll(
                    widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(
                            userId, ScopeType.PERSONAL, 0L));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 参照系（自己スコープ・混入しないこと）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DashboardController の自己スコープ参照系")
    class SelfScopedReads {

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getPersonalDashboard は 200 で自分の個人ダッシュボードを返す")
        void getPersonalDashboard_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard").param("priority", "CRITICAL"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getNotices は自分宛のお知らせのみを返す")
        void getNotices_は自分宛のみ返す() throws Exception {
            // scope_type は NOT NULL（NotificationEntity.java:64-65）。個人ダッシュボードの
            // お知らせは特定チーム/組織に紐付かないため NotificationScopeType.PERSONAL を用いる
            // （本番実装でも個人宛通知は同様に PERSONAL を使う。例: ContactRequestService.java:265）。
            notificationRepository.saveAndFlush(NotificationEntity.builder()
                    .userId(ME).notificationType("SYSTEM_ANNOUNCEMENT")
                    .title("自分宛").body("本文").sourceType("SYSTEM").sourceId(1L)
                    .scopeType(NotificationScopeType.PERSONAL).build());
            notificationRepository.saveAndFlush(NotificationEntity.builder()
                    .userId(OTHER).notificationType("SYSTEM_ANNOUNCEMENT")
                    .title("他人宛").body("本文").sourceType("SYSTEM").sourceId(1L)
                    .scopeType(NotificationScopeType.PERSONAL).build());

            mockMvc.perform(get("/api/v1/dashboard/notices"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(1))
                    .andExpect(jsonPath("$.data.items[0].title").value("自分宛"));
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getMyPosts は 200 で自分の投稿一覧を返す")
        void getMyPosts_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/my-posts"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items.length()").value(0));
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getPersonalTodos は 200 で自分のTODOサマリーを返す")
        void getPersonalTodos_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/todos"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getUnreadThreads は 200 で自分の未読集計を返す")
        void getUnreadThreads_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/unread-threads"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getActivity は 200 で自分の所属スコープの活動フィードを返す")
        void getActivity_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/activity"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getCalendar は 200 で自分のカレンダーサマリーを返す")
        void getCalendar_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/calendar"))
                    .andExpect(status().isOk());
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("DashboardController#getChatHub は 200 で自分のチャットハブを返す")
        void getChatHub_は正常に取得できる() throws Exception {
            mockMvc.perform(get("/api/v1/dashboard/chat-hub"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 更新系（自己スコープ・他ユーザーの行には触れないこと）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DashboardController#resetWidgetSettings")
    class ResetWidgetSettings {

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("resetWidgetSettings は呼び出しユーザー自身のウィジェット設定行のみを削除する")
        void resetWidgetSettings_は自分の行のみ削除する() throws Exception {
            widgetSettingRepository.save(DashboardWidgetSettingEntity.builder()
                    .userId(ME).scopeType(ScopeType.PERSONAL).scopeId(0L)
                    .widgetKey("NOTICES").isVisible(false).sortOrder(0).build());
            widgetSettingRepository.save(DashboardWidgetSettingEntity.builder()
                    .userId(OTHER).scopeType(ScopeType.PERSONAL).scopeId(0L)
                    .widgetKey("NOTICES").isVisible(false).sortOrder(0).build());

            mockMvc.perform(delete("/api/v1/dashboard/widgets").param("scopeType", "PERSONAL"))
                    .andExpect(status().isNoContent());

            assertThat(widgetSettingRepository
                    .findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(ME, ScopeType.PERSONAL, 0L))
                    .isEmpty();
            assertThat(widgetSettingRepository
                    .findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(OTHER, ScopeType.PERSONAL, 0L))
                    .hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ChatFolderController
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ChatFolderController#getFolders / #createFolder")
    class ChatFolderSelfScoped {

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("getFolders は自分のフォルダのみを返す")
        void getFolders_は自分のフォルダのみ返す() throws Exception {
            folderRepository.save(ChatContactFolderEntity.builder()
                    .userId(ME).name("自分用").sortOrder(0).build());
            folderRepository.save(ChatContactFolderEntity.builder()
                    .userId(OTHER).name("他人用").sortOrder(0).build());

            mockMvc.perform(get("/api/v1/chat-folders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].name").value("自分用"));
        }

        @Test
        @WithMockUser(username = "916501")
        @DisplayName("createFolder は呼び出しユーザーを所有者として作成する")
        void createFolder_は本人所有で作られる() throws Exception {
            mockMvc.perform(post("/api/v1/chat-folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", "新規フォルダ"))))
                    .andExpect(status().isCreated());

            List<ChatContactFolderEntity> mine = folderRepository.findByUserIdOrderBySortOrder(ME);
            assertThat(mine).extracting(ChatContactFolderEntity::getName).contains("新規フォルダ");
            assertThat(folderRepository.findByUserIdOrderBySortOrder(OTHER))
                    .extracting(ChatContactFolderEntity::getName)
                    .doesNotContain("新規フォルダ");
        }
    }
}
