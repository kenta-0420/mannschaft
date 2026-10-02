package com.mannschaft.app.social;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.social.service.FriendNotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.5 Phase 2 フレンドチーム受信通知一覧
 * （{@code GET /api/v1/teams/{id}/friend-notifications}）の<b>実 MySQL 契約テスト</b>。
 *
 * <p><b>背景</b>: ダッシュボードの通知フォルダ 500 障害
 * （{@code NotificationRepository#findByUserIdAndScopeTypeAndScopeIdInOrderByCreatedAtDesc} の
 * {@code String} 引数と enum 属性の型不一致）の調査中に、<b>同一の地雷が本番稼働経路にもう 2 本</b>
 * あることが判明した。{@link FriendNotificationService#listFriendNotifications} が呼ぶ</p>
 * <ul>
 *   <li>{@code findByScopeTypeAndScopeIdOrderByCreatedAtDesc}（isRead 未指定時）</li>
 *   <li>{@code findByScopeTypeAndScopeIdAndIsReadOrderByCreatedAtDesc}（isRead 指定時）</li>
 * </ul>
 * <p>はいずれも {@code scopeType} を {@code String} で受けており、
 * 呼び出し側は {@code NotificationScopeType.FRIEND_TEAM.name()} を渡している。</p>
 *
 * <p><b>既存 {@code FriendNotificationServiceTest} は完全モックのため偽グリーンだった。</b>
 * リポジトリをモックすると「{@code .name()} の文字列で呼ばれたこと」しか検証できず、
 * Hibernate のパラメータ束縛が起きないため型不一致が永久に検出されない。
 * 本テストは実 MySQL・実 Hibernate に対して当該クエリを実行してこれを機械的に固定する。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-F1: isRead 未指定でフレンド受信通知一覧が例外なく取得でき、
 *       {@code scope_type = FRIEND_TEAM} かつ {@code scope_id = teamId} の通知のみ返る</li>
 *   <li>AC-F2: {@code isRead=false} で未読のみに絞り込める</li>
 *   <li>AC-F3: {@code isRead=true} で既読のみに絞り込める</li>
 *   <li>AC-F4: 別 scope_type（TEAM）の同一 scope_id 通知は混入しない</li>
 * </ul>
 */
@DisplayName("フレンド受信通知一覧 実DB契約テスト (F01.5 Phase 2)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FriendNotificationScopeQueryContractIT extends AbstractMySqlIntegrationTest {

    /**
     * ロール解決はクロスドメイン（role / membership）であり、Flyway 無効の
     * {@code ddl-auto=create} では roles / user_roles が未シードのため権限判定のみ隔離する。
     * 本テストの責務は「実 Hibernate によるクエリ実行」であって認可判定ではない。
     */
    @MockitoBean
    private AccessControlService accessControlService;

    @Autowired
    private FriendNotificationService friendNotificationService;

    @Autowired
    private NotificationRepository notificationRepository;

    private static final Long USER_ID = 915401L;
    private static final Long RECEIVING_TEAM_ID = 7201L;

    @BeforeEach
    void setUp() {
        Page<NotificationEntity> existing =
                notificationRepository.findByUserIdOrderByCreatedAtDesc(USER_ID, Pageable.unpaged());
        notificationRepository.deleteAll(existing.getContent());

        // FRIEND_TEAM 未読 2 件
        save(NotificationScopeType.FRIEND_TEAM, RECEIVING_TEAM_ID, "フレンド未読1", false);
        save(NotificationScopeType.FRIEND_TEAM, RECEIVING_TEAM_ID, "フレンド未読2", false);
        // FRIEND_TEAM 既読 1 件
        save(NotificationScopeType.FRIEND_TEAM, RECEIVING_TEAM_ID, "フレンド既読1", true);
        // 同一 scope_id だが scope_type が TEAM（混入してはならない）
        save(NotificationScopeType.TEAM, RECEIVING_TEAM_ID, "通常チーム通知", false);
        // 別チーム宛の FRIEND_TEAM 通知（混入してはならない）
        save(NotificationScopeType.FRIEND_TEAM, 7299L, "別チーム宛", false);
    }

    @Test
    @DisplayName("AC-F1/AC-F4: isRead 未指定で FRIEND_TEAM × teamId の通知のみ 3 件返る")
    void ACF1_フレンド受信通知一覧が例外にならず返る() {
        Page<NotificationResponse> page = friendNotificationService.listFriendNotifications(
                RECEIVING_TEAM_ID, USER_ID, null, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).extracting(NotificationResponse::getScopeType)
                .containsOnly(NotificationScopeType.FRIEND_TEAM.name());
        assertThat(page.getContent()).extracting(NotificationResponse::getTitle)
                .containsExactlyInAnyOrder("フレンド未読1", "フレンド未読2", "フレンド既読1");
    }

    @Test
    @DisplayName("AC-F2: isRead=false で未読 2 件のみ返る")
    void ACF2_未読フィルタが効く() {
        Page<NotificationResponse> page = friendNotificationService.listFriendNotifications(
                RECEIVING_TEAM_ID, USER_ID, false, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).extracting(NotificationResponse::getTitle)
                .containsExactlyInAnyOrder("フレンド未読1", "フレンド未読2");
    }

    @Test
    @DisplayName("AC-F3: isRead=true で既読 1 件のみ返る")
    void ACF3_既読フィルタが効く() {
        Page<NotificationResponse> page = friendNotificationService.listFriendNotifications(
                RECEIVING_TEAM_ID, USER_ID, true, PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("フレンド既読1");
    }

    private void save(NotificationScopeType scopeType, Long scopeId, String title, boolean read) {
        NotificationEntity entity = NotificationEntity.builder()
                .userId(USER_ID)
                .notificationType("FRIEND_ANNOUNCEMENT")
                .title(title)
                .body("本文")
                .sourceType("FRIEND_TEAM")
                .sourceId(1L)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
        if (read) {
            entity.markAsRead();
        }
        notificationRepository.saveAndFlush(entity);
    }
}
