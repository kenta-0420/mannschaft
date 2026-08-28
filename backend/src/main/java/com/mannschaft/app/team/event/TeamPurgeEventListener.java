package com.mannschaft.app.team.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;

/**
 * 30 日後物理削除（{@link AccountPurgedEvent}）を購読し、
 * team ドメインの {@code team_org_memberships} 行の {@code invited_by} / {@code responded_by}
 * を NULL 化する。
 *
 * <p>{@link TeamOrgMembershipRepository#nullifyInvitedBy(Long)} および
 * {@link TeamOrgMembershipRepository#nullifyRespondedBy(Long)} を呼び出すのみで、
 * メンバーシップ行自体は削除しない（メンバーシップ削除は role ドメインの
 * {@code RolePurgeEventListener} 経由で {@code MembershipChangedEvent(REMOVED)} を発火させる）。</p>
 *
 * <p><b>三重防御パターン:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 * </p>
 *
 * <p><b>既存越境 DML との関係:</b>
 * 現状 {@code AccountPurgeService#purgeUser}（gdpr/auth ドメイン）は同じ NULL 化 DML を
 * 直接呼んでいる（{@code AccountPurgeService.java:172-173}）。本リスナーが導入されることで
 * Phase B 併走期間中は二重実行になるが、SET ... = NULL は冪等のため機能影響なし。
 * 既存越境 DML は Phase C で撤去予定。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §4 Phase B-2 / PR #837 (Phase B-1 role) と同型パターン。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamPurgeEventListener {

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、対象ユーザーが招待者 / 応答者となっている
     * {@code team_org_memberships} 行を NULL 化する。
     *
     * <p>{@code nullifyInvitedBy} 失敗時も {@code nullifyRespondedBy} を継続実行する
     * （GDPR 30 日タイムリミット遵守のため）。失敗件は WARN ログを残し、
     * 夜次補正バッチで再処理する運用とする。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会アカウントの消去イベントを購読しチームドメインの個人データを消す。止めると GDPR 第17条の消去期限を破り、イベントは再生されない")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        int nullifiedInvitedBy = 0;
        int nullifiedRespondedBy = 0;
        boolean invitedByFailed = false;
        boolean respondedByFailed = false;

        try {
            nullifiedInvitedBy = teamOrgMembershipRepository.nullifyInvitedBy(userId);
        } catch (Exception e) {
            invitedByFailed = true;
            log.warn("ユーザー退会 team purge: nullifyInvitedBy 失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        try {
            nullifiedRespondedBy = teamOrgMembershipRepository.nullifyRespondedBy(userId);
        } catch (Exception e) {
            respondedByFailed = true;
            log.warn("ユーザー退会 team purge: nullifyRespondedBy 失敗 userId={}, error={}",
                    userId, e.getMessage(), e);
        }

        log.info("ユーザー退会 team purge 完了: userId={}, nullifiedInvitedBy={}, nullifiedRespondedBy={}, invitedByFailed={}, respondedByFailed={}",
                userId, nullifiedInvitedBy, nullifiedRespondedBy, invitedByFailed, respondedByFailed);

        // Phase D-8: 処理完了を completion_status に記録（両操作とも失敗なしの場合のみ SUCCESS とする）
        if (!invitedByFailed && !respondedByFailed) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "team")
                    .ifPresent(entity -> {
                        entity.setStatus("SUCCESS");
                        entity.setCompletedAt(LocalDateTime.now());
                        completionStatusRepository.save(entity);
                    });
        }
    }

    /**
     * 管理者からの手動 retry 用。{@link #on(AccountPurgedEvent)} と同じドメイン操作を実行するが、
     * {@code completionStatusRepository} の更新は {@code GdprPurgeRetryService} が担う。
     *
     * @param userId retry 対象ユーザー ID
     * @return true=全操作成功、false=1 件以上失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        boolean invitedByFailed = false;
        boolean respondedByFailed = false;

        try {
            teamOrgMembershipRepository.nullifyInvitedBy(userId);
        } catch (Exception e) {
            invitedByFailed = true;
            log.warn("team purge retry: nullifyInvitedBy 失敗 userId={}", userId, e);
        }

        try {
            teamOrgMembershipRepository.nullifyRespondedBy(userId);
        } catch (Exception e) {
            respondedByFailed = true;
            log.warn("team purge retry: nullifyRespondedBy 失敗 userId={}", userId, e);
        }

        return !invitedByFailed && !respondedByFailed;
    }
}
