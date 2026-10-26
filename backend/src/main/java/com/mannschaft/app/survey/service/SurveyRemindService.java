package com.mannschaft.app.survey.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.SurveyErrorCode;
import com.mannschaft.app.survey.SurveyStatus;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyResponseEntity;
import com.mannschaft.app.survey.event.SurveyReminderNotificationEvent;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.survey.repository.SurveyResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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

    /** CMP-041: ADMIN+ 委任判定に用いる permission 名。 */
    private static final String PERMISSION_MANAGE_SURVEYS = "MANAGE_SURVEYS";

    /** 督促のクールダウン時間（時間単位）。 */
    private static final long REMIND_COOLDOWN_HOURS = 24L;

    private final SurveyRepository surveyRepository;
    private final SurveyResponseRepository responseRepository;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;
    /** 母集団解決の唯一の窓口（公開時の target_count スナップショットと同一定義）。 */
    private final SurveyUniverseResolver universeResolver;

    /**
     * 未回答者へ督促通知を送信する（F05.4 督促 API）。
     *
     * <p>認可・状態・クールダウン・上限を順にチェックし、未回答メンバーへ通知を送信する。
     * 通知は受信者を確定して publish するのみで、配送は AFTER_COMMIT の非同期リスナーが行う
     * （Issue #2997 G5）。応答の件数は「対象人数」で、配送完了ではなく受付を意味する。
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

        // 認可: 作成者 or ADMIN+ のみ督促を送信可能（getRespondents の認可パターンに準拠。
        // MANAGE_SURVEYS 保有 DEPUTY_ADMIN へ委任・CMP-041）
        boolean isCreator = survey.getCreatedBy() != null && survey.getCreatedBy().equals(currentUserId);
        boolean isAdmin = accessControlService.hasAdminOrPermissionInScope(
                currentUserId, survey.getScopeId(), survey.getScopeType(), PERMISSION_MANAGE_SURVEYS);
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

        // 通知（配信＝受信権 統一・関所(1)通知）:
        // unansweredUserIds は配信母集団から回答済みを除いたもので、全員が事前認可済み。
        // Issue #2997 G5: 業務TXは受信者を確定して publish するだけ。配送（事前認可バルク・locale 別文面）は
        // AFTER_COMMIT の SurveyReminderNotificationListener が行う。これにより業務ロールバック時に通知だけが
        // 残らず、通知側の失敗が督促カウンタの確定を巻き戻さない。0 人なら publish しない。
        if (!unansweredUserIds.isEmpty()) {
            eventPublisher.publishEvent(new SurveyReminderNotificationEvent(
                    SurveyReminderNotificationEvent.Kind.REMIND,
                    surveyId,
                    survey.getScopeType(),
                    survey.getScopeId(),
                    survey.getTitle(),
                    unansweredUserIds,
                    currentUserId,
                    null));
        }

        // カウンタ更新
        survey.recordManualRemind(now);
        surveyRepository.save(survey);

        int remindedCount = unansweredUserIds.size();
        int newCount = survey.getManualRemindCount() != null ? survey.getManualRemindCount() : 0;
        int remainingQuota = MAX_MANUAL_REMIND_COUNT - newCount;
        String message = remindedCount + "名の未回答メンバーにリマインドの送信を受け付けました。";

        log.info("アンケート督促: surveyId={}, remindedCount={}, manualRemindCount={}, by={}",
                surveyId, remindedCount, newCount, currentUserId);

        return new RemindResponse(surveyId, remindedCount, remainingQuota, message);
    }

    /**
     * 未回答ユーザーIDを抽出する。
     *
     * <p>母集団は {@link SurveyEntity#getDistributionMode()} に応じて切り替える:
     * <ul>
     *   <li>{@link DistributionMode#ALL} → スコープ内全メンバー（組織は配下 ACTIVE チームまで再帰展開）</li>
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
     * <p>母集団の定義は {@link SurveyUniverseResolver} に一元化されている。
     * ここで自前に分岐を書き戻すと、公開時にスナップショットする分母（{@code target_count}）と
     * 督促の宛先が別々の母集団を見ることになる（Issue #2787 / CMP-042）。</p>
     *
     * @param survey 対象アンケート
     * @return 母集団ユーザーIDリスト
     */
    private List<Long> resolveUniverseUserIds(SurveyEntity survey) {
        return universeResolver.resolveUniverseUserIds(survey);
    }
}
