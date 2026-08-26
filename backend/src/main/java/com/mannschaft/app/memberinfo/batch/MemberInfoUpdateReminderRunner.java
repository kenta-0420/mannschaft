package com.mannschaft.app.memberinfo.batch;

import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseRepository;
import com.mannschaft.app.memberinfo.event.MemberInfoUpdateReminderNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * F14.2 メンバー情報更新リマインドの「1 メンバーぶん」を実行する {@link Propagation#REQUIRES_NEW} 実行 Bean
 * （Issue #2834 / CMP-056 第2群ロット2。金型: {@code QuickMemoReminderRunner}・CMP-035）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@code MemberInfoUpdateReminderBatchService#run} に {@code @Transactional} が付き、
 * 全チーム・全メンバー（最大500人）を 1 トランザクションで包んだままメンバー単位に catch していた。
 * 1 メンバーぶんの DB 例外が rollback-only を残すため、catch して続行した<b>他メンバーの
 * {@code last_reminder_sent_at} もコミット時にまとめて巻き戻り</b>、24時間クールダウンが効かず
 * 翌日実行で全員へ二重にリマインドが飛びうる状態だった。</p>
 *
 * <h2>再実行安全性（冪等）</h2>
 * <p>抽出時点のスナップショットを信じず、独立トランザクション内でフィールドと回答を読み直して
 * 「期限切れ・未回答が残っているか」「24時間以内に送信済みでないか」を<b>再判定</b>してから記録する。
 * 二重起動・再実行時に同じメンバーへ二度送ることはない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberInfoUpdateReminderRunner {

    /** リマインドのクールダウン（時間）。 */
    private static final int COOLDOWN_HOURS = 24;

    private final TeamMemberInfoFieldRepository fieldRepository;
    private final TeamMemberInfoResponseRepository responseRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 1 メンバーぶんのリマインドを独立トランザクションで確定し、通知配送要求を publish する。
     *
     * <p>publish した通知配送要求は {@code AFTER_COMMIT} でのみ発火するため、
     * このトランザクションがロールバックすれば通知は作られない。</p>
     *
     * @param teamId   チームID
     * @param userId   受信者ユーザーID
     * @param fieldIds 抽出時点で対象だったフィールドID（独立TX内で読み直して再判定する）
     * @param now      判定基準時刻
     * @return 通知配送要求を publish した場合は {@code true}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markReminderSent(Long teamId, Long userId, List<Long> fieldIds, LocalDateTime now) {
        if (fieldIds == null || fieldIds.isEmpty()) {
            return false;
        }

        // 抽出後にフィールドが無効化・更新されている可能性があるため読み直す（冪等性の要）。
        List<TeamMemberInfoFieldEntity> targetFields = fieldRepository.findAllById(fieldIds).stream()
                .filter(f -> teamId.equals(f.getTeamId()))
                .filter(f -> Boolean.TRUE.equals(f.getIsActive()))
                .filter(f -> f.getRefreshIntervalMonths() != null)
                .toList();
        if (targetFields.isEmpty()) {
            return false;
        }

        Map<Long, TeamMemberInfoResponseEntity> responseByFieldId = new HashMap<>();
        for (TeamMemberInfoResponseEntity resp : responseRepository.findByTeamIdAndUserId(teamId, userId)) {
            responseByFieldId.put(resp.getFieldId(), resp);
        }

        LocalDateTime cooldownThreshold = now.minusHours(COOLDOWN_HOURS);
        boolean hasOverdueOrMissing = false;
        for (TeamMemberInfoFieldEntity field : targetFields) {
            TeamMemberInfoResponseEntity resp = responseByFieldId.get(field.getId());
            if (resp != null && resp.getLastReminderSentAt() != null
                    && resp.getLastReminderSentAt().isAfter(cooldownThreshold)) {
                // 24時間以内に送信済み。二重送信しない（是正前と同じ判定を独立TX内で再実行する）。
                return false;
            }
            if (isOverdue(resp, field, now)) {
                hasOverdueOrMissing = true;
            }
        }
        if (!hasOverdueOrMissing) {
            return false;
        }

        for (TeamMemberInfoFieldEntity field : targetFields) {
            TeamMemberInfoResponseEntity resp = responseByFieldId.get(field.getId());
            if (resp == null) {
                // 未回答の場合は新規作成
                responseRepository.save(TeamMemberInfoResponseEntity.builder()
                        .teamId(teamId)
                        .userId(userId)
                        .fieldId(field.getId())
                        .lastReminderSentAt(now)
                        .build());
            } else {
                // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
                // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
                resp.updateLastReminderSentAt(now);
                responseRepository.save(resp);
            }
        }

        eventPublisher.publishEvent(new MemberInfoUpdateReminderNotificationEvent(
                teamId, userId, targetFields.get(0).getId()));
        return true;
    }

    /**
     * フィールドの refreshIntervalMonths に基づき、回答が期限切れか未回答かを判定する。
     *
     * @param resp  対象ユーザーのフィールドへの回答（未回答の場合は {@code null}）
     * @param field 対象フィールド
     * @param now   判定基準時刻
     * @return 期限切れまたは未回答の場合は {@code true}
     */
    private boolean isOverdue(TeamMemberInfoResponseEntity resp, TeamMemberInfoFieldEntity field, LocalDateTime now) {
        if (field.getRefreshIntervalMonths() == null) {
            return false;
        }
        if (resp == null || resp.getConfirmedAt() == null) {
            return true;
        }
        return resp.getConfirmedAt().plusMonths(field.getRefreshIntervalMonths()).isBefore(now);
    }
}
