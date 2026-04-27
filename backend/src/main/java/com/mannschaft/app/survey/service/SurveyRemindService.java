package com.mannschaft.app.survey.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyNotificationType;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * アンケート督促サービス。F05.4 督促 API（手動リマインド）の責務を担う。
 *
 * <p>BACKEND_CODING_CONVENTION §2.2（DI 7 個超過は分割検討）に従い、
 * {@link SurveyService} から督促関連メソッドを切り出した。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SurveyRemindService {

    /** 督促回数の上限（手動リマインド）。 */
    private static final int MAX_MANUAL_REMIND_COUNT = 3;

    /** 督促のクールダウン時間（時間単位）。 */
    private static final long REMIND_COOLDOWN_HOURS = 24L;

    private final SurveyRepository surveyRepository;
    private final SurveyTargetRepository targetRepository;
    private final SurveyResponseRepository responseRepository;
    private final UserRoleRepository userRoleRepository;
    private final AccessControlService accessControlService;
    private final NotificationHelper notificationHelper;

    /**
     * 未回答者へ督促通知を送信する（F05.4 督促 API）。
     *
     * <p>認可・状態・クールダウン・上限を順にチェックし、未回答メンバーへ通知を送信する。
     * 通知送信は {@link NotificationHelper#notifyAll} 経由で個別の失敗を握りつつ継続するため、
     * 一部ユーザーへの送信失敗があっても全体ロールバックは発生しない（onboarding 流儀に準拠）。
     * カウンタ更新（{@code lastRemindedAt} / {@code manualRemindCount}）は同一トランザクションで永続化する。</p>
     *
     * <p>母集団の決定（設計書 §1035-1036 に準拠）:
     * <ul>
     *   <li>{@code distribution_mode = ALL} → スコープ内全メンバー（user_roles 経由）</li>
     *   <li>{@code distribution_mode = TARGETED} → {@code survey_targets} 登録ユーザー</li>
     * </ul>
     * 既に回答済みのユーザーは除外する。
     * </p>
     *
     * @param surveyId      対象アンケートID
     * @param currentUserId 操作実行者ユーザーID
     * @return 督促結果（送信人数・残回数・案内メッセージ）
     * @throws BusinessException SURVEY_NOT_FOUND / REMIND_PERMISSION_DENIED /
     *                           INVALID_SURVEY_STATUS / REMIND_COOLDOWN_NOT_ELAPSED /
     *                           REMIND_QUOTA_EXCEEDED
     */
    @Transactional
    public RemindResponse remind(Long surveyId, Long currentUserId) {
        SurveyEntity survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND));

        // 認可: 作成者 or ADMIN+ のみ督促を送信可能（getRespondents の認可パターンに準拠）
        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.isAdminOrAbove(
                currentUserId, survey.getScopeId(), survey.getScopeType());
        if (!isCreator && !isAdmin) {
            throw new BusinessException(SurveyErrorCode.REMIND_PERMISSION_DENIED);
        }

        // 状態: PUBLISHED のみ督促可能
        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new BusinessException(SurveyErrorCode.INVALID_SURVEY_STATUS);
        }

        // クールダウン: 前回送信から24時間経過していなければ拒否
        LocalDateTime now = LocalDateTime.now();
        if (survey.getLastRemindedAt() != null
                && Duration.between(survey.getLastRemindedAt(), now).toHours() < REMIND_COOLDOWN_HOURS) {
            throw new BusinessException(SurveyErrorCode.REMIND_COOLDOWN_NOT_ELAPSED);
        }

        // 上限: 手動リマインドは MAX_MANUAL_REMIND_COUNT 回まで
        if (survey.getManualRemindCount() != null
                && survey.getManualRemindCount() >= MAX_MANUAL_REMIND_COUNT) {
            throw new BusinessException(SurveyErrorCode.REMIND_QUOTA_EXCEEDED);
        }

        // 未回答者抽出（distribution_mode に応じて母集団を切り替え。回答済みを除外）
        List<Long> unansweredUserIds = findUnansweredUserIds(survey);

        // 通知送信（NotificationHelper.notifyAll が個別の失敗を握りつつ継続する）
        NotificationScopeType notifScope = "TEAM".equals(survey.getScopeType())
                ? NotificationScopeType.TEAM
                : NotificationScopeType.ORGANIZATION;
        if (!unansweredUserIds.isEmpty()) {
            notificationHelper.notifyAll(
                    unansweredUserIds,
                    SurveyNotificationType.SURVEY_RESPONSE_REMINDER.name(),
                    "アンケート未回答のお知らせ",
                    "「" + survey.getTitle() + "」が未回答です。回答にご協力ください。",
                    "SURVEY",
                    surveyId,
                    notifScope,
                    survey.getScopeId(),
                    "/surveys/" + surveyId,
                    currentUserId);
        }

        // カウンタ更新
        survey.recordManualRemind(now);
        surveyRepository.save(survey);

        int remindedCount = unansweredUserIds.size();
        int newCount = survey.getManualRemindCount() != null ? survey.getManualRemindCount() : 0;
        int remainingQuota = MAX_MANUAL_REMIND_COUNT - newCount;
        String message = remindedCount + "名の未回答メンバーにリマインドを送信しました。";

        log.info("アンケート督促: surveyId={}, remindedCount={}, manualRemindCount={}, by={}",
                surveyId, remindedCount, newCount, currentUserId);

        return new RemindResponse(surveyId, remindedCount, remainingQuota, message);
    }

    /**
     * 未回答ユーザーIDを抽出する。
     *
     * <p>母集団は {@link SurveyEntity#getDistributionMode()} に応じて切り替える:
     * <ul>
     *   <li>{@link DistributionMode#ALL} → {@code user_roles} 経由でスコープ内全メンバーを取得</li>
     *   <li>{@link DistributionMode#TARGETED} → {@code survey_targets} 登録ユーザー</li>
     * </ul>
     * 設計書 F05.4 §1035-1036 を参照。回答済みユーザーは母集団から除外する。
     */
    private List<Long> findUnansweredUserIds(SurveyEntity survey) {
        List<Long> universeUserIds = resolveUniverseUserIds(survey);
        if (universeUserIds.isEmpty()) {
            return List.of();
        }
        Set<Long> respondedUserIds = new HashSet<>();
        for (SurveyResponseEntity r : responseRepository.findBySurveyIdOrderByCreatedAtAsc(survey.getId())) {
            respondedUserIds.add(r.getUserId());
        }
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long uid : universeUserIds) {
            if (uid == null || respondedUserIds.contains(uid) || !seen.add(uid)) {
                continue;
            }
            result.add(uid);
        }
        return result;
    }

    /**
     * distribution_mode に応じて母集団ユーザーIDリストを取得する。
     *
     * @param survey 対象アンケート
     * @return 母集団ユーザーIDリスト（順序は配信モードのソース順）
     */
    private List<Long> resolveUniverseUserIds(SurveyEntity survey) {
        if (survey.getDistributionMode() == DistributionMode.ALL) {
            return userRoleRepository.findUserIdsByScope(survey.getScopeType(), survey.getScopeId());
        }
        // TARGETED: survey_targets が母集団
        List<SurveyTargetEntity> targets = targetRepository.findBySurveyId(survey.getId());
        List<Long> ids = new ArrayList<>(targets.size());
        for (SurveyTargetEntity t : targets) {
            ids.add(t.getUserId());
        }
        return ids;
    }
}
