package com.mannschaft.app.team.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * F15.4 Phase 4: {@link MembershipChangedEvent} を受信して
 * {@code teams.member_count} を同期更新するリスナー。
 *
 * <p>RoleService の {@code assignRole / changeRole / removeMember / leaveScope /
 * transferOwnership} が発火する {@link MembershipChangedEvent} を
 * {@link TransactionPhase#AFTER_COMMIT} で受信し、TEAM スコープの場合のみ
 * {@code teams.member_count} を +1 / -1 する。</p>
 *
 * <h3>イベント種別ごとの挙動</h3>
 * <ul>
 *   <li>{@link MembershipChangedEvent.ChangeType#ASSIGNED} — +1（新規ロール割当）</li>
 *   <li>{@link MembershipChangedEvent.ChangeType#REMOVED}  — -1（除名・退会）</li>
 *   <li>{@link MembershipChangedEvent.ChangeType#CHANGED}  — no-op（既存メンバーのロール変更で人数不変）</li>
 * </ul>
 *
 * <h3>クロスドメイン境界</h3>
 * <p>本リスナーは {@code role} ドメインのイベントを受けて {@code team} ドメインのテーブルを更新する。
 * イベント駆動による疎結合のため CLAUDE.md 原則 5（@Transactional はドメイン内に閉じる）には抵触しない。</p>
 *
 * <h3>整合性保証</h3>
 * <p>{@code assignRole} は内部で「既存削除 → 新規 save」を行うが {@code ASSIGNED} イベントを 1 回しか発火しない。
 * このため、既存ロールを上書きするケースでは {@code member_count} が +1 だけ進んでしまう（実数は不変なのに）。
 * 同様に {@code transferOwnership} の {@code CHANGED}×2 はカウント不変で問題ないが、
 * 上記のような微少誤差は夜次バッチ（足軽17 担当）で {@code COUNT(user_roles)} と突合し補正する設計。</p>
 *
 * <p>設計書: docs/features/F15.4_team_store_search_within_org.md §3.3 / §11.4</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamMemberCountListener {

    private static final String SCOPE_TYPE_TEAM = "TEAM";

    private final TeamRepository teamRepository;

    /**
     * メンバーシップ変更イベントを受信し、TEAM スコープの場合に
     * {@code teams.member_count} を同期更新する。
     *
     * <p>{@link TransactionPhase#AFTER_COMMIT} で動作するため、ロール変更が DB にコミット
     * された後に member_count を更新する。RoleService のトランザクション外で UPDATE を発行
     * するため新規トランザクション ({@link Propagation#REQUIRES_NEW}) を起動する。</p>
     *
     * <p>例外が発生しても他のリスナーを止めないよう WARN ログに留めて続行する（バッチで補正可能）。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。所属変更に伴う teams.member_count の増減であり、日次バックフィルが同じ値へ収束させる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMembershipChanged(MembershipChangedEvent event) {
        if (!SCOPE_TYPE_TEAM.equals(event.scopeType())) {
            return; // ORGANIZATION スコープは対象外
        }

        try {
            switch (event.changeType()) {
                case ASSIGNED -> {
                    int updated = teamRepository.incrementMemberCount(event.scopeId());
                    log.debug("TeamMemberCountListener: +1 (teamId={}, userId={}, updated={})",
                            event.scopeId(), event.userId(), updated);
                }
                case REMOVED -> {
                    int updated = teamRepository.decrementMemberCount(event.scopeId());
                    log.debug("TeamMemberCountListener: -1 (teamId={}, userId={}, updated={})",
                            event.scopeId(), event.userId(), updated);
                }
                case CHANGED -> {
                    // 既存メンバーのロール変更は人数不変のためカウント更新不要
                }
            }
        } catch (Exception ex) {
            // best-effort 同期。失敗してもサービス全体は止めず夜次バッチで補正する
            log.warn("TeamMemberCountListener: member_count 同期更新失敗 "
                    + "(teamId={}, userId={}, changeType={})",
                    event.scopeId(), event.userId(), event.changeType(), ex);
        }
    }
}
