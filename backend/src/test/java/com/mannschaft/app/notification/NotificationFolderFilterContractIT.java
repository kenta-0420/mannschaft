package com.mannschaft.app.notification;

import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F15.3 マイスコープフォルダによる通知フィルタ（{@code GET /api/v1/notifications?folderId=&scopeType=}）の
 * <b>実 MySQL 契約テスト</b>。
 *
 * <p><b>背景（試練 / red 先行）</b>: ダッシュボードのフォルダタブ描画時に発火する
 * {@code GET /api/v1/notifications?folderId=5&scopeType=TEAM&size=5&page=0} が実機で HTTP 500 を返す。
 * 原因は {@code NotificationRepository#findByUserIdAndScopeTypeAndScopeIdInOrderByCreatedAtDesc} の
 * 第 2 引数が {@code String} でありながら、比較対象の {@code NotificationEntity#scopeType} が
 * {@code @Enumerated(EnumType.STRING)} の enum 属性であるため、Hibernate のパラメータ束縛で
 * 型不一致例外が発生していたことにある。</p>
 *
 * <p><b>この経路はこれまで実 DB・実 Hibernate に対して一度も実行されていなかった</b>
 * （{@code NotificationControllerTest} は {@code folderId=null} 経路のみ、
 * {@code MyScopeFolderNotificationFilterTest} は完全モック）。モックでは原理的に検出できない
 * 不具合であるため、本テストは Testcontainers の実 MySQL 上で当該クエリを実行する。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-1: {@code GET /api/v1/notifications?folderId=&scopeType=TEAM&size=5&page=0} が
 *       500 ではなく 200 を返し、フォルダ内スコープの通知のみを含む</li>
 *   <li>AC-2: {@code NotificationService#listNotificationsByFolder} が
 *       フォルダ内 scopeId の通知のみを返す（フォルダ外 scopeId・他ユーザー分は混入しない）</li>
 *   <li>AC-3: scopeType が実際に効いている（同一 scopeId でも ORGANIZATION 通知は TEAM フォルダに出ない）</li>
 *   <li>AC-4: ORGANIZATION フォルダでは ORGANIZATION 通知のみが返る（TEAM 通知は出ない）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@DisplayName("通知フォルダフィルタ 契約テスト (F15.3 / ダッシュボード 500 根治)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFolderFilterContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private MyScopeFolderRepository folderRepository;

    @Autowired
    private MyScopeFolderItemRepository itemRepository;

    /** 本テスト専用のユーザー（他 IT のデータを壊さないよう固有値を使う）。 */
    private static final Long USER_ID = 915301L;
    private static final Long OTHER_USER_ID = 915302L;

    private static final Long TEAM_IN_FOLDER_A = 7101L;
    private static final Long TEAM_IN_FOLDER_B = 7102L;
    private static final Long TEAM_OUTSIDE_FOLDER = 7103L;
    private static final Long ORG_IN_FOLDER = 8101L;

    private Long teamFolderId;
    private Long orgFolderId;

    @BeforeEach
    void setUp() {
        cleanUpFor(USER_ID);
        cleanUpFor(OTHER_USER_ID);

        // TEAM フォルダ: TEAM_IN_FOLDER_A / TEAM_IN_FOLDER_B を格納
        teamFolderId = createFolder(USER_ID, ScopeType.TEAM, "所属チーム");
        addItem(teamFolderId, TEAM_IN_FOLDER_A, 0);
        addItem(teamFolderId, TEAM_IN_FOLDER_B, 1);

        // ORGANIZATION フォルダ: ORG_IN_FOLDER を格納
        orgFolderId = createFolder(USER_ID, ScopeType.ORGANIZATION, "所属組織");
        addItem(orgFolderId, ORG_IN_FOLDER, 0);

        // フォルダ内 TEAM 通知（返るべき 2 件）
        saveNotification(USER_ID, NotificationScopeType.TEAM, TEAM_IN_FOLDER_A, "フォルダ内A");
        saveNotification(USER_ID, NotificationScopeType.TEAM, TEAM_IN_FOLDER_B, "フォルダ内B");
        // フォルダ外 TEAM 通知（返ってはならない）
        saveNotification(USER_ID, NotificationScopeType.TEAM, TEAM_OUTSIDE_FOLDER, "フォルダ外");
        // 同一 scope_id だが scope_type 違い（scopeType 条件が効いていることの証跡）
        saveNotification(USER_ID, NotificationScopeType.ORGANIZATION, TEAM_IN_FOLDER_A, "スコープ種別違い");
        // ORGANIZATION フォルダ用の通知
        saveNotification(USER_ID, NotificationScopeType.ORGANIZATION, ORG_IN_FOLDER, "組織フォルダ内");
        // 他ユーザーの通知（返ってはならない）
        saveNotification(OTHER_USER_ID, NotificationScopeType.TEAM, TEAM_IN_FOLDER_A, "他人の通知");
    }

    @Test
    @WithMockUser(username = "915301")
    @DisplayName("AC-1: GET /api/v1/notifications?folderId=&scopeType=TEAM が 500 にならず 200 でフォルダ内 2 件を返す")
    void AC1_フォルダ指定の通知一覧が500にならない() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")
                        .param("folderId", String.valueOf(teamFolderId))
                        .param("scopeType", "TEAM")
                        .param("page", "0")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("AC-2: listNotificationsByFolder はフォルダ内 scopeId の通知のみを返す")
    void AC2_フォルダ内スコープの通知のみ返る() {
        Page<NotificationResponse> page = notificationService.listNotificationsByFolder(
                USER_ID, teamFolderId, ScopeType.TEAM, PageRequest.of(0, 5));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(NotificationResponse::getTitle)
                .containsExactlyInAnyOrder("フォルダ内A", "フォルダ内B");
        assertThat(page.getContent()).extracting(NotificationResponse::getUserId)
                .containsOnly(USER_ID);
    }

    @Test
    @DisplayName("AC-3: scope_type 条件が効いており、同一 scope_id の ORGANIZATION 通知は TEAM フォルダに出ない")
    void AC3_スコープ種別条件が効いている() {
        Page<NotificationResponse> page = notificationService.listNotificationsByFolder(
                USER_ID, teamFolderId, ScopeType.TEAM, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(NotificationResponse::getScopeType)
                .containsOnly(NotificationScopeType.TEAM.name());
        assertThat(page.getContent()).extracting(NotificationResponse::getTitle)
                .doesNotContain("スコープ種別違い");
    }

    @Test
    @DisplayName("AC-4: ORGANIZATION フォルダでは ORGANIZATION 通知のみが返る")
    void AC4_組織フォルダは組織通知のみ返る() {
        Page<NotificationResponse> page = notificationService.listNotificationsByFolder(
                USER_ID, orgFolderId, ScopeType.ORGANIZATION, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("組織フォルダ内");
        assertThat(page.getContent().get(0).getScopeType())
                .isEqualTo(NotificationScopeType.ORGANIZATION.name());
    }

    // ---------- フィクスチャ補助 ----------

    private Long createFolder(Long userId, ScopeType scopeType, String name) {
        return folderRepository.saveAndFlush(MyScopeFolderEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .name(name)
                .sortOrder(0)
                .isDefault(Boolean.FALSE)
                .build()).getId();
    }

    private void addItem(Long folderId, Long scopeId, int sortOrder) {
        itemRepository.saveAndFlush(MyScopeFolderItemEntity.builder()
                .folderId(folderId)
                .scopeId(scopeId)
                .sortOrder(sortOrder)
                .build());
    }

    private void saveNotification(Long userId, NotificationScopeType scopeType, Long scopeId, String title) {
        notificationRepository.saveAndFlush(NotificationEntity.builder()
                .userId(userId)
                .notificationType("SYSTEM_ANNOUNCEMENT")
                .title(title)
                .body("本文")
                .sourceType("SYSTEM")
                .sourceId(1L)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build());
    }

    /**
     * 対象ユーザー分の通知・フォルダ・アイテムのみを削除する。
     *
     * <p>{@code deleteAll()} で全消しすると同一 ApplicationContext を共有する他 IT の
     * フィクスチャを巻き込むため、ユーザー単位に限定して掃除する。</p>
     */
    private void cleanUpFor(Long userId) {
        Page<NotificationEntity> existing =
                notificationRepository.findByUserIdOrderByCreatedAtDesc(userId, Pageable.unpaged());
        notificationRepository.deleteAll(existing.getContent());

        for (ScopeType scopeType : ScopeType.values()) {
            List<MyScopeFolderEntity> folders = folderRepository
                    .findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(userId, scopeType);
            if (folders.isEmpty()) {
                continue;
            }
            List<Long> folderIds = folders.stream().map(MyScopeFolderEntity::getId).toList();
            itemRepository.deleteAll(itemRepository.findByFolderIdIn(folderIds));
            folderRepository.deleteAll(folders);
        }
    }
}
