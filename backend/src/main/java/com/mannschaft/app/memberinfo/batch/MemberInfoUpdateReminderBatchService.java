package com.mannschaft.app.memberinfo.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoResponseRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * メンバー情報更新リマインダーバッチサービス。
 *
 * <p>F14.2 チームメンバー情報管理機能の一部。refreshIntervalMonths が設定された
 * フィールドに対して、期限切れまたは未回答のメンバーへ毎日9時（JST）に通知を送信する。</p>
 *
 * <p>設計書: docs/features/F14.2_member_info.md</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberInfoUpdateReminderBatchService {

    private static final int BATCH_LIMIT = 500;

    private final TeamMemberInfoFieldRepository fieldRepository;
    private final TeamMemberInfoResponseRepository responseRepository;
    private final MembershipRepository membershipRepository;
    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * 毎日9時（JST）に実行。期限切れまたは未回答フィールドを持つメンバーへリマインドを送信する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。メンバー情報の更新リマインド送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "memberinfo-update-reminder-daily", description = "メンバー情報の期限切れ・未回答に対するリマインドを毎日 09:00 に送信する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "memberInfoUpdateReminderBatch", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    @Transactional
    public void run() {
        log.info("メンバー情報更新リマインダーバッチ開始");

        // 1. refreshIntervalMonths が設定されているフィールドを持つ全チームIDを特定
        List<Long> teamIds = fieldRepository.findDistinctTeamIdsWithRefreshInterval();
        if (teamIds.isEmpty()) {
            log.info("対象チームなし。バッチ終了");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cooldownThreshold = now.minusHours(24);
        int totalSentCount = 0;
        int processedUserCount = 0;

        for (Long teamId : teamIds) {
            if (processedUserCount >= BATCH_LIMIT) {
                log.info("BATCH_LIMIT({})に達したため、残りのチームは翌日に繰り越し: 処理済みチームID={}", BATCH_LIMIT, teamId);
                break;
            }

            // 2. 各チームのアクティブメンバー一覧を取得
            List<MembershipEntity> memberships = membershipRepository.findAllActiveByScope(
                    ScopeType.TEAM, teamId);
            if (memberships.isEmpty()) {
                continue;
            }

            // 3. チームのアクティブフィールド一覧を取得
            List<TeamMemberInfoFieldEntity> fields =
                    fieldRepository.findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId);
            // refreshIntervalMonths が設定されているフィールドのみ対象
            List<TeamMemberInfoFieldEntity> targetFields = fields.stream()
                    .filter(f -> f.getRefreshIntervalMonths() != null)
                    .collect(Collectors.toList());
            if (targetFields.isEmpty()) {
                continue;
            }

            // 4. チーム全メンバーのレスポンスを一括取得してキャッシュ（N+1防止）
            List<Long> fieldIds = targetFields.stream()
                    .map(TeamMemberInfoFieldEntity::getId)
                    .collect(Collectors.toList());
            List<TeamMemberInfoResponseEntity> allResponses =
                    responseRepository.findByFieldIdIn(fieldIds);
            // (userId, fieldId) → response のマップ
            Map<String, TeamMemberInfoResponseEntity> responseMap = allResponses.stream()
                    .collect(Collectors.toMap(
                            r -> r.getUserId() + "_" + r.getFieldId(),
                            r -> r,
                            (a, b) -> a));

            // Issue #2715 CMP-055 ロットC-5: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
            // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
            Map<Long, String> teamLocales;
            try {
                teamLocales = userLocaleCache.getLocales(
                        memberships.stream()
                                .map(MembershipEntity::getUserId)
                                .filter(java.util.Objects::nonNull)
                                .toList());
            } catch (Exception e) {
                log.warn("locale 一括解決に失敗（既定 locale で継続）: teamId={}, error={}", teamId, e.getMessage());
                teamLocales = Map.of();
            }

            // 5. 各メンバーについて通知判定
            for (MembershipEntity membership : memberships) {
                if (processedUserCount >= BATCH_LIMIT) {
                    break;
                }
                Long userId = membership.getUserId();
                if (userId == null) {
                    // GDPRマスキング済みユーザーはスキップ
                    continue;
                }

                processedUserCount++;

                // 期限切れまたは未回答フィールドが1つ以上あるか確認
                boolean hasOverdueOrMissing = targetFields.stream()
                        .anyMatch(field -> {
                            TeamMemberInfoResponseEntity resp =
                                    responseMap.get(userId + "_" + field.getId());
                            return isOverdue(resp, field);
                        });
                if (!hasOverdueOrMissing) {
                    continue;
                }

                // last_reminder_sent_at が24時間以内ならスキップ
                boolean sentRecently = targetFields.stream()
                        .anyMatch(field -> {
                            TeamMemberInfoResponseEntity resp =
                                    responseMap.get(userId + "_" + field.getId());
                            return resp != null
                                    && resp.getLastReminderSentAt() != null
                                    && resp.getLastReminderSentAt().isAfter(cooldownThreshold);
                        });
                if (sentRecently) {
                    continue;
                }

                // 通知送信
                try {
                    String firstFieldName = targetFields.get(0).getFieldName();
                    Locale locale = Locale.forLanguageTag(teamLocales.getOrDefault(userId, "ja"));
                    notificationHelper.notify(
                            userId, "MEMBER_INFO_UPDATE_REMINDER",
                            messageSource.getMessage(
                                    "notification.memberinfo.updateReminder.title", null,
                                    "情報の更新をお願いします", locale),
                            messageSource.getMessage(
                                    "notification.memberinfo.updateReminder.body",
                                    new Object[]{firstFieldName},
                                    "「" + firstFieldName + "」等の情報を更新してください。", locale),
                            "TEAM_MEMBER_INFO", teamId,
                            NotificationScopeType.TEAM, teamId,
                            "/teams/" + teamId + "/member-info", null);

                    // 対象フィールドの responses.last_reminder_sent_at を NOW() に更新
                    for (TeamMemberInfoFieldEntity field : targetFields) {
                        TeamMemberInfoResponseEntity resp =
                                responseMap.get(userId + "_" + field.getId());
                        if (resp == null) {
                            // 未回答の場合は新規作成
                            TeamMemberInfoResponseEntity newResp = TeamMemberInfoResponseEntity.builder()
                                    .teamId(teamId)
                                    .userId(userId)
                                    .fieldId(field.getId())
                                    .lastReminderSentAt(now)
                                    .build();
                            responseRepository.save(newResp);
                        } else {
                            // managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する
                            // （toBuilder().build()→save は継承フィールド id を引き継がず INSERT 化するため廃止）
                            resp.updateLastReminderSentAt(now);
                            responseRepository.save(resp);
                        }
                    }

                    totalSentCount++;
                } catch (Exception e) {
                    log.warn("リマインド通知送信失敗: teamId={}, userId={}, error={}",
                            teamId, userId, e.getMessage());
                }
            }
        }

        log.info("メンバー情報更新リマインダーバッチ完了: 送信件数={}", totalSentCount);
    }

    /**
     * フィールドのrefreshIntervalMonthsに基づき、レスポンスが期限切れか未回答かを判定する。
     *
     * @param resp  対象ユーザーのフィールドへの回答（未回答の場合はnull）
     * @param field 対象フィールド
     * @return 期限切れまたは未回答の場合はtrue
     */
    private boolean isOverdue(TeamMemberInfoResponseEntity resp, TeamMemberInfoFieldEntity field) {
        if (field.getRefreshIntervalMonths() == null) return false;
        if (resp == null || resp.getConfirmedAt() == null) return true;
        return resp.getConfirmedAt().plusMonths(field.getRefreshIntervalMonths())
                .isBefore(LocalDateTime.now());
    }
}
