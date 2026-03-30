package com.mannschaft.app.gamification.event;

import com.mannschaft.app.auth.event.LoginSuccessEvent;
import com.mannschaft.app.gamification.ActionType;
import com.mannschaft.app.gamification.service.GamificationPointService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timeline.event.TimelinePostCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * ゲーミフィケーション・ポイント付与イベントリスナー。
 * 各ドメインイベントを受信し、ゲーミフィケーションポイントを付与する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GamificationPointListener {

    private final GamificationPointService gamificationPointService;
    private final UserRoleRepository userRoleRepository;

    /**
     * タイムライン投稿作成イベントを受信し、TIMELINE_POSTポイントを付与する。
     * スコープ（TEAM / ORGANIZATION）にポイントルールが設定されている場合のみ付与する。
     *
     * @param event タイムライン投稿作成イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("event-pool")
    public void handleTimelinePostCreated(TimelinePostCreatedEvent event) {
        log.debug("タイムライン投稿イベント受信: postId={}, userId={}, scopeType={}",
                event.getPostId(), event.getUserId(), event.getScopeType());
        gamificationPointService.addPoint(
                event.getUserId(),
                event.getScopeType(),
                event.getScopeId(),
                ActionType.TIMELINE_POST,
                "TIMELINE_POST",
                event.getPostId()
        );
    }

    /**
     * ログイン成功イベントを受信し、デイリーログインポイントを付与する。
     *
     * <p>ユーザーが所属する全チーム・全組織に対してポイントを付与する。
     * ゲーミフィケーションが無効なスコープはポイントサービス内部でスキップされる。</p>
     *
     * @param event ログイン成功イベント
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("event-pool")
    public void handleDailyLogin(LoginSuccessEvent event) {
        Long userId = event.getUserId();
        log.debug("デイリーログインイベント受信: userId={}", userId);

        // ユーザーが所属する全チームにポイント付与
        List<Long> teamIds = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId)
                .stream()
                .map(r -> r.getTeamId())
                .distinct()
                .toList();

        for (Long teamId : teamIds) {
            gamificationPointService.addPoint(
                    userId, "TEAM", teamId,
                    ActionType.DAILY_LOGIN, "DAILY_LOGIN", userId
            );
        }

        // ユーザーが所属する全組織にポイント付与
        List<Long> orgIds = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId)
                .stream()
                .map(r -> r.getOrganizationId())
                .distinct()
                .toList();

        for (Long orgId : orgIds) {
            gamificationPointService.addPoint(
                    userId, "ORGANIZATION", orgId,
                    ActionType.DAILY_LOGIN, "DAILY_LOGIN", userId
            );
        }

        log.info("デイリーログインポイント付与完了: userId={}, teams={}, orgs={}",
                userId, teamIds.size(), orgIds.size());
    }
}
