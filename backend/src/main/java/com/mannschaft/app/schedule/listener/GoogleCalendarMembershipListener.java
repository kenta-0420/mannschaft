package com.mannschaft.app.schedule.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.membership.event.MembershipEndedEvent;
import com.mannschaft.app.schedule.service.GoogleCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F03.3 堅牢化フェーズ AC-7: メンバーシップ終了（退会・サポータ解除）に連動して、当該ユーザー×スコープの
 * Google カレンダー同期設定を無効化し、Google 側イベントを削除するリスナー。
 *
 * <p>{@link MembershipEndedEvent}（{@code MembershipService.leave} の AFTER_COMMIT 相当）を受信し、
 * {@link GoogleCalendarService#handleMembershipEnded} を呼ぶ。退会後も Google カレンダーへ
 * スケジュールが push され続ける穴を塞ぐ。手本:
 * {@link ScheduleDelegationMembershipListener}。</p>
 *
 * <p>クロスドメインデッドロック回避＋セキュリティ無効化が leave 側 tx の throw で巻き戻らないよう、
 * {@code AFTER_COMMIT} + {@code REQUIRES_NEW} で処理する（CLAUDE.md 原則5 /
 * memory: feedback_security_invalidation_rolled_back_by_same_tx_throw）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleCalendarMembershipListener {

    private final GoogleCalendarService googleCalendarService;

    /**
     * メンバー退会時に該当ユーザー×スコープのカレンダー同期を無効化＋Google イベント削除する。
     *
     * @param event メンバーシップ終了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。所属終了に伴う Google カレンダー連携の解除。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMembershipEnded(MembershipEndedEvent event) {
        try {
            googleCalendarService.handleMembershipEnded(
                    event.userId(), event.scopeType().name(), event.scopeId());
        } catch (Exception ex) {
            // AFTER_COMMIT のベストエフォート掃除。失敗は記録するが他リスナーを巻き込まない
            // （症状は握り潰さずログに残す）。
            log.warn("退会連動 カレンダー同期無効化失敗: userId={}, scopeType={}, scopeId={}, error={}",
                    event.userId(), event.scopeType(), event.scopeId(), ex.getMessage(), ex);
        }
    }
}
