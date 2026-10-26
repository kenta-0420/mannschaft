package com.mannschaft.app.role.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * {@link com.mannschaft.app.role.event.RolePurgeEventListener} の処理漏れを夜次補正するバッチ。
 *
 * <p>{@code user_roles} テーブルに残存する孤児レコード（{@code users} テーブルに対応する行が存在しない
 * user_id を参照する {@code user_roles} 行）を検出し、{@link RoleService#removeMemberWithoutAdminCheck}
 * 経由で削除することで {@code MembershipChangedEvent(REMOVED)} を適切に発火させる。</p>
 *
 * <p><b>孤児の定義:</b>
 * {@code user_roles.user_id} が指す {@code users} レコードが物理削除済み（存在しない）であること。
 * {@link com.mannschaft.app.gdpr.service.AccountPurgeService} による 30 日後物理削除が完了した後、
 * {@link com.mannschaft.app.role.event.RolePurgeEventListener} の処理が失敗した場合に発生する。</p>
 *
 * <p><b>なぜ直接 DELETE しないか:</b>
 * {@link RoleService#removeMemberWithoutAdminCheck} を経由することで
 * {@code MembershipChangedEvent(REMOVED)} が自然発火し、{@code TeamMemberCountListener}（F15.4 Phase 4）
 * による member_count 即時減算が保証される。直接 DELETE では同イベントが発火しないため、
 * 統計データとの不整合が生じる。</p>
 *
 * <p><b>SYSTEM_ADMIN 行の扱い:</b>
 * {@code team_id} と {@code organization_id} がともに NULL の SYSTEM_ADMIN 行は
 * {@code removeMemberWithoutAdminCheck} が要求する scopeId/scopeType を構築できないため
 * スキップし WARN ログに記録する。詳細は兄弟設計書 §6 Phase α-1 を参照。</p>
 *
 * <p>設計根拠:
 * {@code docs/architecture/account_purge_cross_domain_refactor.md} §3.5 + §4 Phase D-2</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRolePurgeBackfillBatchService {

    // RoleService 経由でイベント発火するため処理コストが高く、小さめに設定する
    private static final int BATCH_SIZE = 50;

    private final UserRoleRepository userRoleRepository;
    private final RoleService roleService;

    /**
     * {@code user_roles} 孤児補正バッチ。毎日 03:00（JST）に実行する。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>孤児 userId を {@link UserRoleRepository#findOrphanUserIds} で最大 {@value BATCH_SIZE} 件取得</li>
     *   <li>各 userId の全ロール割当を {@link UserRoleRepository#findAllByUserId} で取得</li>
     *   <li>スコープが判明している行を {@link RoleService#removeMemberWithoutAdminCheck} で削除</li>
     *   <li>スコープ不明（SYSTEM_ADMIN）行はスキップして WARN ログに記録</li>
     *   <li>1 件失敗しても他の userId / ロール行の処理を継続する</li>
     * </ol>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると論理削除済みユーザーロールの物理削除 backfill が進まず、消したはずの権限行が残って認可判定に影響する")
    @BatchEndpoint(
            name = "user-role-purge-backfill-daily",
            description = "AccountPurgedEvent 処理漏れの user_roles を毎日 03:00 に補正する"
    )
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "userRolePurgeBackfillBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void backfill() {
        List<Long> orphanUserIds = userRoleRepository.findOrphanUserIds(PageRequest.of(0, BATCH_SIZE));

        if (orphanUserIds.isEmpty()) {
            log.info("user_roles 孤児補正: 対象なし");
            return;
        }

        log.info("user_roles 孤児補正: 対象 userId={}件", orphanUserIds.size());

        int successCount = 0;
        int failedCount = 0;
        int skippedCount = 0;

        for (Long userId : orphanUserIds) {
            try {
                List<UserRoleEntity> roles = userRoleRepository.findAllByUserId(userId);
                for (UserRoleEntity role : roles) {
                    try {
                        Long scopeId;
                        String scopeType;

                        if (role.getOrganizationId() != null) {
                            scopeId = role.getOrganizationId();
                            scopeType = "ORGANIZATION";
                        } else if (role.getTeamId() != null) {
                            scopeId = role.getTeamId();
                            scopeType = "TEAM";
                        } else {
                            log.warn("user_roles 孤児補正: scopeId/scopeType 不明な行をスキップ" +
                                    " userRoleId={}, userId={}", role.getId(), userId);
                            skippedCount++;
                            continue;
                        }

                        roleService.removeMemberWithoutAdminCheck(scopeId, scopeType, userId);
                    } catch (Exception e) {
                        log.error("user_roles 孤児補正: ロール行削除失敗" +
                                " userRoleId={}, userId={}", role.getId(), userId, e);
                        failedCount++;
                    }
                }
                successCount++;
            } catch (Exception e) {
                log.error("user_roles 孤児補正: userId={} の処理で例外発生", userId, e);
                failedCount++;
            }
        }

        log.info("user_roles 孤児補正完了: 対象={}件, 成功={}件, 失敗={}件, スキップ={}件",
                orphanUserIds.size(), successCount, failedCount, skippedCount);
    }
}
