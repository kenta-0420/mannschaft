package com.mannschaft.app.memberinfo.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldEntity;
import com.mannschaft.app.memberinfo.TeamMemberInfoFieldRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メンバー情報更新リマインダーバッチサービス。
 *
 * <p>F14.2 チームメンバー情報管理機能の一部。refreshIntervalMonths が設定された
 * フィールドに対して、期限切れまたは未回答のメンバーへ毎日9時（JST）に通知を送信する。</p>
 *
 * <p>設計書: docs/features/F14.2_member_info.md</p>
 *
 * <h2>Issue #2834 / CMP-056 第2群ロット2 による是正</h2>
 * <p>是正前は<b>バッチ全体を 1 つの {@code @Transactional} で包みながらメンバー単位で catch</b> していた。
 * 1 メンバーの失敗は握りつぶされたように見えて、実際には rollback-only が残るため
 * <b>全メンバーの {@code last_reminder_sent_at} がコミット時に巻き戻り</b>、24時間クールダウンが
 * 効かなくなっていた。非トランザクションのオーケストレータ ＋ メンバーごと
 * {@link MemberInfoUpdateReminderRunner}（{@code REQUIRES_NEW}）＋ {@code AFTER_COMMIT} 通知の形へ
 * 是正した（CMP-035 の金型）。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code team_member_info_responses.last_reminder_sent_at}）を
 * 更新する</b>。この列は24時間クールダウンの判定に使われる冪等キーであり、通知と同時に確定しなければ
 * ならない。よって確定設計の「バッチで業務状態も更新する」に該当し、非TXループ →
 * メンバーごと REQUIRES_NEW → その中の {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>外向き契約</h2>
 * <p>{@code run} は是正前後とも戻り値 {@code void}。{@code @BatchEndpoint} 経由の管理コンソール実行も
 * 戻り値を持たないため、FE / OpenAPI への波及はない。ログ上の「送信件数」は
 * <b>「通知の到達件数」ではなく「リマインドを確定したメンバー数」</b>を意味するようになった
 * （非同期化により、バッチ終了時点では配送結果が判明しないため）。</p>
 *
 * <h2>ロケール解決の移動</h2>
 * <p>是正前はここで {@code UserLocaleCache#getLocales} によるチーム単位のバルク解決を行っていたが、
 * 通知の組み立てが配送リスナー側へ移ったため、解決も配送リスナー（受信者 1 名ぶん）へ移した。
 * 本バッチは通知対象が「期限切れかつクールダウン外のメンバー」に絞られており、実際に解決が必要な
 * 人数は少数であるため、チーム全員ぶんを先読みするより往復が減る。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberInfoUpdateReminderBatchService {

    private static final int BATCH_LIMIT = 500;

    private final TeamMemberInfoFieldRepository fieldRepository;
    private final MembershipRepository membershipRepository;
    private final MemberInfoUpdateReminderRunner memberInfoUpdateReminderRunner;

    /**
     * 毎日9時（JST）に実行。期限切れまたは未回答フィールドを持つメンバーへリマインドを送信する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。メンバー情報の更新リマインド送信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "memberinfo-update-reminder-daily", description = "メンバー情報の期限切れ・未回答に対するリマインドを毎日 09:00 に送信する")
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "memberInfoUpdateReminderBatch", lockAtMostFor = "30m", lockAtLeastFor = "5m")
    public void run() {
        log.info("メンバー情報更新リマインダーバッチ開始");

        // 1. refreshIntervalMonths が設定されているフィールドを持つ全チームIDを特定（TX 外）
        List<Long> teamIds = fieldRepository.findDistinctTeamIdsWithRefreshInterval();
        if (teamIds.isEmpty()) {
            log.info("対象チームなし。バッチ終了");
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        int totalSentCount = 0;
        int processedUserCount = 0;
        int failed = 0;
        Long firstFailedUserId = null;

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

            // 3. チームのアクティブフィールドのうち refreshIntervalMonths が設定されているものを対象にする。
            //    実際の判定は Runner が独立TX内で読み直して行う（冪等性の要）。ここでは候補IDだけを渡す。
            List<Long> targetFieldIds = fieldRepository
                    .findByTeamIdAndIsActiveTrueOrderBySortOrderAsc(teamId).stream()
                    .filter(f -> f.getRefreshIntervalMonths() != null)
                    .map(TeamMemberInfoFieldEntity::getId)
                    .toList();
            if (targetFieldIds.isEmpty()) {
                continue;
            }

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

                try {
                    if (memberInfoUpdateReminderRunner.markReminderSent(teamId, userId, targetFieldIds, now)) {
                        totalSentCount++;
                    }
                } catch (Exception e) {
                    // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                    // rollback-only のトランザクションで記録が消える。
                    failed++;
                    if (firstFailedUserId == null) {
                        firstFailedUserId = userId;
                    }
                    log.error("メンバー情報更新リマインドの確定に失敗（継続）: teamId={}, userId={}", teamId, userId, e);
                }
            }
        }

        String summary = "メンバー情報更新リマインダーバッチ完了: 確定={}, 走査ユーザー={}, 失敗={}, firstFailedUserId={}";
        if (failed > 0) {
            log.error(summary, totalSentCount, processedUserCount, failed, firstFailedUserId);
        } else {
            log.info(summary, totalSentCount, processedUserCount, failed, firstFailedUserId);
        }
    }
}
