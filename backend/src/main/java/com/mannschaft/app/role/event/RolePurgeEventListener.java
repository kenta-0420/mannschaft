package com.mannschaft.app.role.event;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 30 日後物理削除（{@link AccountPurgedEvent}）を購読し、
 * role ドメインの {@code user_roles} 行を {@link RoleService#removeMemberWithoutAdminCheck} 経由で削除する。
 *
 * <p>各 {@link UserRoleEntity} に対し scopeId / scopeType を引数に
 * {@code removeMemberWithoutAdminCheck} を呼ぶことで、{@code MembershipChangedEvent(REMOVED)} が
 * 自然発火し、既存 {@code TeamMemberCountListener}（F15.4 Phase 4）が即時減算する。
 * これにより F15.4 Caveat（{@code AccountPurgeService} が {@code MembershipChangedEvent} を
 * 未発火だった問題）が自動解消される（親設計書 §3.5）。</p>
 *
 * <p><b>三重防御パターン:</b>
 * <ul>
 *   <li>{@code @Async("event-pool")} — 呼び出し元 TX とスレッド分離</li>
 *   <li>{@code @TransactionalEventListener(AFTER_COMMIT)} — gdpr 側コミット成立後に実行</li>
 *   <li>{@code @Transactional(REQUIRES_NEW)} — 独立した新規 TX</li>
 * </ul>
 * </p>
 *
 * <p><b>SYSTEM_ADMIN 行の扱い:</b>
 * {@code team_id} と {@code organization_id} がともに NULL の SYSTEM_ADMIN 行は
 * {@code removeMemberWithoutAdminCheck} が要求する scopeId/scopeType を構築できないため
 * スキップする（WARN ログを残す）。SYSTEM_ADMIN は退会前にプラットフォーム監査で
 * 別途処理される運用前提（兄弟設計書 §6 Phase α-1）。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md}
 * §3.5 + §4 Phase B-1 + 兄弟設計書 §6 Phase α-1（PR #825）</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RolePurgeEventListener {

    private final UserRoleRepository userRoleRepository;
    private final RoleService roleService;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * {@link AccountPurgedEvent} を購読し、対象ユーザーの全 user_roles を
     * {@code removeMemberWithoutAdminCheck} 経由で削除する。
     *
     * <p>1 件削除失敗しても他のスコープ削除は継続する（GDPR 30 日タイムリミットを優先）。
     * 失敗件は WARN ログとして残し、夜次補正バッチで再処理する運用とする。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "退会アカウントの消去イベントを購読しロール割当の個人データを消す。止めると GDPR 第17条の消去期限を破り、イベントは再生されない")
    @Async("purge-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void on(AccountPurgedEvent event) {
        Long userId = event.getUserId();
        List<UserRoleEntity> userRoles = userRoleRepository.findAllByUserId(userId);
        int removed = 0;
        int skipped = 0;
        int failed = 0;
        for (UserRoleEntity userRole : userRoles) {
            try {
                Long scopeId;
                String scopeType;
                if (userRole.getOrganizationId() != null) {
                    scopeId = userRole.getOrganizationId();
                    scopeType = "ORGANIZATION";
                } else if (userRole.getTeamId() != null) {
                    scopeId = userRole.getTeamId();
                    scopeType = "TEAM";
                } else {
                    log.warn("ユーザー退会 role purge: scopeId/scopeType 不明な user_role をスキップ: userRoleId={}, userId={}",
                            userRole.getId(), userId);
                    skipped++;
                    continue;
                }
                roleService.removeMemberWithoutAdminCheck(scopeId, scopeType, userId);
                removed++;
            } catch (Exception e) {
                log.warn("ユーザー退会 role purge: 削除失敗 userRoleId={}, userId={}, error={}",
                        userRole.getId(), userId, e.getMessage(), e);
                failed++;
            }
        }
        log.info("ユーザー退会 role purge 完了: userId={}, removedScopes={}, skipped={}, failed={}",
                userId, removed, skipped, failed);

        // Phase D-8: 処理完了を completion_status に記録（失敗が 0 件の場合のみ SUCCESS とする）
        if (failed == 0) {
            completionStatusRepository.findByUserIdAndDomainName(userId, "role")
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
     * <p>SYSTEM_ADMIN 行（team_id / organization_id 両方 null）はスキップして成功扱いとする
     * （{@link #on(AccountPurgedEvent)} と同じポリシー）。</p>
     *
     * @param userId retry 対象ユーザー ID
     * @return true=全操作成功（0 件失敗）、false=1 件以上失敗
     */
    @Transactional
    public boolean retryPurge(Long userId) {
        List<UserRoleEntity> userRoles = userRoleRepository.findAllByUserId(userId);
        int failed = 0;
        for (UserRoleEntity userRole : userRoles) {
            try {
                Long scopeId;
                String scopeType;
                if (userRole.getOrganizationId() != null) {
                    scopeId = userRole.getOrganizationId();
                    scopeType = "ORGANIZATION";
                } else if (userRole.getTeamId() != null) {
                    scopeId = userRole.getTeamId();
                    scopeType = "TEAM";
                } else {
                    log.warn("role purge retry: scopeId/scopeType 不明な user_role をスキップ: userRoleId={}, userId={}",
                            userRole.getId(), userId);
                    continue;
                }
                roleService.removeMemberWithoutAdminCheck(scopeId, scopeType, userId);
            } catch (Exception e) {
                log.warn("role purge retry 失敗 userId={} userRoleId={}", userId, userRole.getId(), e);
                failed++;
            }
        }
        return failed == 0;
    }
}
