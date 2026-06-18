package com.mannschaft.app.survey.listener;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.SurveyNotificationType;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.event.SurveyPublishedEvent;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * アンケート公開イベントを受信し、配信母集団へ公開通知（{@code SURVEY_CREATED}）を送信するリスナー。
 *
 * <p>設計書 F05.4 §1528 で {@code SURVEY_CREATED}＝「公開時にスコープ内全メンバーへ通知」が
 * enum 定義されていながら未発火だった既存乖離を、本リスナーで実装する。</p>
 *
 * <p><b>規模対応 Tier2（マスター御裁可済み）</b>:
 * <ul>
 *   <li>{@code AFTER_COMMIT} + {@code @Async("event-pool")} で動作するため、{@code publishSurvey}
 *       本体はステータス更新をコミットして即返しし、受信者ループ（通知行作成）は本スレッドで非同期実行する。
 *       これにより数万人規模でも公開 API 応答をブロックしない。</li>
 *   <li>配信タスクはコミット済みのイベント内容のみを読む（自己呼び出しによる {@code @Async} 無効化を回避し、
 *       別 Bean に切り出している）。</li>
 *   <li>個別ユーザーへの送信失敗は {@link NotificationHelper#notifyAll} 内で握りつつ継続する。</li>
 * </ul>
 * </p>
 *
 * <p><b>越境是正</b>: 組織スコープ×ALL の配信母集団は
 * {@link OrganizationMembershipService#resolveOrgDistributionUserIds(Long, boolean)}
 * 経由で「直属 ∪ 配下ACTIVEチーム」を展開する。{@code team_org_memberships}/{@code memberships}
 * は直接参照しない。</p>
 *
 * <p><b>TODO（規模対応 Tier3）</b>: 数万規模の組織では、ここで同期的に通知行を INSERT する方式すら
 * イベントスレッドを長時間占有する。将来は配信ジョブ（バッチ・キュー投入）に切り出し、
 * チャンク単位で進行・再実行可能にすることが望ましい。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyPublishNotificationListener {

    private final OrganizationMembershipService organizationMembershipService;
    private final UserRoleRepository userRoleRepository;
    private final SurveyTargetRepository surveyTargetRepository;
    private final NotificationHelper notificationHelper;

    /**
     * アンケート公開イベントを受信して配信母集団へ公開通知を送信する。
     *
     * @param event アンケート公開イベント
     */
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurveyPublished(SurveyPublishedEvent event) {
        try {
            List<Long> recipients = resolveRecipients(event);
            if (recipients.isEmpty()) {
                return;
            }
            NotificationScopeType notifScope = "TEAM".equals(event.getScopeType())
                    ? NotificationScopeType.TEAM
                    : NotificationScopeType.ORGANIZATION;
            // TODO（Tier3）: 数万規模では notifyAll の受信者ループをジョブ化（チャンク投入）する。
            notificationHelper.notifyAll(
                    recipients,
                    SurveyNotificationType.SURVEY_CREATED.name(),
                    "新しいアンケートが公開されました",
                    "「" + event.getTitle() + "」が公開されました。回答にご協力ください。",
                    "SURVEY",
                    event.getSurveyId(),
                    notifScope,
                    event.getScopeId(),
                    "/surveys/" + event.getSurveyId(),
                    event.getActorId());
            log.info("アンケート公開通知送信: surveyId={}, recipientCount={}",
                    event.getSurveyId(), recipients.size());
        } catch (Exception e) {
            log.warn("アンケート公開通知の送信に失敗: surveyId={}", event.getSurveyId(), e);
        }
    }

    /**
     * 配信母集団を解決する。
     *
     * <ul>
     *   <li>{@link DistributionMode#ALL} × 組織スコープ → 直属 ∪ 配下ACTIVEチーム（応援者トグル適用）</li>
     *   <li>{@link DistributionMode#ALL} × チームスコープ → 当該チームメンバー（配下展開なし・現状維持）</li>
     *   <li>{@link DistributionMode#TARGETED} → {@code survey_targets} 登録ユーザー</li>
     * </ul>
     *
     * @param event アンケート公開イベント
     * @return 配信対象ユーザーIDリスト
     */
    private List<Long> resolveRecipients(SurveyPublishedEvent event) {
        if (event.getDistributionMode() == DistributionMode.ALL) {
            if ("ORGANIZATION".equals(event.getScopeType())) {
                // 組織スコープ×ALL: 配下参加チーム展開（応援者トグル適用）
                return organizationMembershipService.resolveOrgDistributionUserIds(
                        event.getScopeId(), event.isIncludeSupporters());
            }
            // チームスコープ×ALL（および COMMITTEE 等）: 配下展開なし・従来挙動を維持
            return userRoleRepository.findUserIdsByScope(event.getScopeType(), event.getScopeId());
        }
        // TARGETED: survey_targets が母集団
        return surveyTargetRepository.findBySurveyId(event.getSurveyId()).stream()
                .map(SurveyTargetEntity::getUserId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }
}
