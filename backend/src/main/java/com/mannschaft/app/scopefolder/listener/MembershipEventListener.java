package com.mannschaft.app.scopefolder.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.membership.event.MembershipEndedEvent;
import com.mannschaft.app.organization.event.OrganizationDeletedEvent;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.service.MyScopeFolderService;
import com.mannschaft.app.team.event.TeamDeletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * マイスコープフォルダの dangling アイテム削除を担うイベントリスナー。
 *
 * <p>設計書 F15.3 §6.5 / §9.5 / §9.6 / §12.2</p>
 *
 * <p>対応イベント:</p>
 * <ul>
 *   <li>{@link MembershipEndedEvent} — 該当ユーザー × scope の items を物理削除</li>
 *   <li>{@link TeamDeletedEvent} — 該当 team_id を全ユーザー分物理削除</li>
 *   <li>{@link OrganizationDeletedEvent} — 該当 organization_id を全ユーザー分物理削除</li>
 * </ul>
 *
 * <p>{@code AFTER_COMMIT} で発火元のトランザクション完了後に処理することで
 * クロスドメインデッドロックを回避（CLAUDE.md 原則 5 / 設計書 §12.2）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MembershipEventListener {

    private final MyScopeFolderService folderService;

    /**
     * メンバーシップ終了時に、該当ユーザーのフォルダから対象 scope のアイテムを削除する。
     *
     * @param event メンバーシップ終了イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。所属終了・チーム削除・組織削除に伴うフォルダ権限の後始末。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleMembershipEnded(MembershipEndedEvent event) {
        try {
            ScopeType folderScopeType = toFolderScopeType(event.scopeType());
            folderService.handleMembershipEnded(event.userId(), folderScopeType, event.scopeId());
        } catch (Exception ex) {
            log.warn("MembershipEndedEvent 処理失敗: userId={}, scopeType={}, scopeId={}, error={}",
                    event.userId(), event.scopeType(), event.scopeId(), ex.getMessage(), ex);
        }
    }

    /**
     * チーム削除時に、全ユーザーのフォルダから該当 team_id を削除する。
     *
     * @param event チーム削除イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。所属終了・チーム削除・組織削除に伴うフォルダ権限の後始末。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTeamDeleted(TeamDeletedEvent event) {
        try {
            folderService.handleScopeDeleted(ScopeType.TEAM, event.getTeamId());
        } catch (Exception ex) {
            log.warn("TeamDeletedEvent 処理失敗: teamId={}, error={}",
                    event.getTeamId(), ex.getMessage(), ex);
        }
    }

    /**
     * 組織削除時に、全ユーザーのフォルダから該当 organization_id を削除する。
     *
     * @param event 組織削除イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。所属終了・チーム削除・組織削除に伴うフォルダ権限の後始末。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrganizationDeleted(OrganizationDeletedEvent event) {
        try {
            folderService.handleScopeDeleted(ScopeType.ORGANIZATION, event.getOrganizationId());
        } catch (Exception ex) {
            log.warn("OrganizationDeletedEvent 処理失敗: orgId={}, error={}",
                    event.getOrganizationId(), ex.getMessage(), ex);
        }
    }

    /**
     * membership.domain.ScopeType を scopefolder.entity.enums.ScopeType に変換する。
     */
    private ScopeType toFolderScopeType(com.mannschaft.app.membership.domain.ScopeType membershipScope) {
        return switch (membershipScope) {
            case TEAM -> ScopeType.TEAM;
            case ORGANIZATION -> ScopeType.ORGANIZATION;
        };
    }
}
