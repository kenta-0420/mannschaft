package com.mannschaft.app.membership.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.HashSet;
import java.util.Set;

/**
 * F00.5 フェーズ 3 — memberships / user_roles 整合性チェックバッチ。
 *
 * <p>毎日 AM4 時に memberships テーブルのアクティブ行（MEMBER/SUPPORTER）と
 * user_roles テーブルの MEMBER/SUPPORTER 行を比較し、差分件数を
 * ログ・Micrometer メトリクス {@code f005.consistency.diff.count} に記録する。</p>
 *
 * <p>Phase 4 完了後（dualWrite.enabled=false 切替後）に本バッチの差分件数が 0 に
 * 収束することを確認してから、二重書き込みコードを物理削除する。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §13.3</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MembershipConsistencyChecker {

    private final MembershipRepository membershipRepository;
    private final UserRoleRepository userRoleRepository;
    private final MeterRegistry meterRegistry;

    @BatchEndpoint(name = "membership-consistency-check-daily", description = "memberships と user_roles の整合性を毎日 04:00 に検査する")
    @Scheduled(cron = "0 0 4 * * *")
    public void checkConsistency() {
        long diffCount = computeDiff();
        if (diffCount > 0) {
            log.warn("F00.5 整合性チェック: memberships と user_roles の差分あり。差分件数={}", diffCount);
        } else {
            log.info("F00.5 整合性チェック: 差分なし（整合性 OK）");
        }
        // Micrometer ゲージに記録
        meterRegistry.gauge("f005.consistency.diff.count", diffCount);
    }

    /**
     * memberships のアクティブ行（left_at IS NULL）と user_roles の TEAM/ORGANIZATION 行を比較し、
     * 対称差の件数（userId × scopeType × scopeId の組み合わせ）を返す。
     *
     * <p>対称差 = (memberships のみに存在する組み合わせ) + (user_roles のみに存在する組み合わせ)</p>
     */
    long computeDiff() {
        final int CHUNK_SIZE = 500;

        // memberships 側: アクティブ（left_at IS NULL）の (userId, scopeType, scopeId) トリプルセット
        // チャンク処理で全件をページングしながら読み込む
        Set<String> membershipKeys = new HashSet<>();
        Pageable membershipPageable = PageRequest.of(0, CHUNK_SIZE);
        Page<MembershipEntity> membershipPage;
        do {
            membershipPage = membershipRepository.findAll(membershipPageable);
            for (MembershipEntity m : membershipPage.getContent()) {
                if (m.getLeftAt() == null && m.getUserId() != null) {
                    String key = m.getUserId() + ":" + m.getScopeType().name() + ":" + m.getScopeId();
                    membershipKeys.add(key);
                }
            }
            membershipPageable = membershipPageable.next();
        } while (membershipPage.hasNext());

        // user_roles 側: TEAM/ORGANIZATION の (userId, scopeType, scopeId) トリプルセット
        // チャンク処理で全件をページングしながら読み込む
        Set<String> userRoleKeys = new HashSet<>();
        Pageable userRolePageable = PageRequest.of(0, CHUNK_SIZE);
        Page<UserRoleEntity> userRolePage;
        do {
            userRolePage = userRoleRepository.findAll(userRolePageable);
            for (UserRoleEntity ur : userRolePage.getContent()) {
                if (ur.getUserId() != null) {
                    if (ur.getTeamId() != null) {
                        userRoleKeys.add(ur.getUserId() + ":TEAM:" + ur.getTeamId());
                    } else if (ur.getOrganizationId() != null) {
                        userRoleKeys.add(ur.getUserId() + ":ORGANIZATION:" + ur.getOrganizationId());
                    }
                }
            }
            userRolePageable = userRolePageable.next();
        } while (userRolePage.hasNext());

        // 対称差: memberships にのみある組み合わせ
        Set<String> onlyInMemberships = new HashSet<>(membershipKeys);
        onlyInMemberships.removeAll(userRoleKeys);

        // 対称差: user_roles にのみある組み合わせ
        Set<String> onlyInUserRoles = new HashSet<>(userRoleKeys);
        onlyInUserRoles.removeAll(membershipKeys);

        long diffCount = onlyInMemberships.size() + onlyInUserRoles.size();

        if (diffCount > 0) {
            log.warn("F00.5 差分詳細: memberships のみ={} 件, user_roles のみ={} 件",
                    onlyInMemberships.size(), onlyInUserRoles.size());
        }

        return diffCount;
    }
}
