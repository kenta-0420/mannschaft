package com.mannschaft.app.membership.batch;

import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
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
        // memberships 側: アクティブ（left_at IS NULL）の全件を取得
        List<MembershipEntity> allMemberships = membershipRepository.findAll().stream()
                .filter(m -> m.getLeftAt() == null && m.getUserId() != null)
                .toList();

        // memberships 側の (userId, scopeType, scopeId) トリプルセット
        Set<String> membershipKeys = new HashSet<>();
        for (MembershipEntity m : allMemberships) {
            String key = m.getUserId() + ":" + m.getScopeType().name() + ":" + m.getScopeId();
            membershipKeys.add(key);
        }

        // user_roles 側: MEMBER/SUPPORTER の行を全件取得（findAll でスキャン）
        List<UserRoleEntity> allUserRoles = userRoleRepository.findAll().stream()
                .filter(ur -> ur.getUserId() != null)
                .toList();

        // user_roles 側の (userId, scopeType, scopeId) トリプルセット（TEAM/ORGANIZATION のみ対象）
        Set<String> userRoleKeys = new HashSet<>();
        for (UserRoleEntity ur : allUserRoles) {
            if (ur.getTeamId() != null) {
                userRoleKeys.add(ur.getUserId() + ":TEAM:" + ur.getTeamId());
            } else if (ur.getOrganizationId() != null) {
                userRoleKeys.add(ur.getUserId() + ":ORGANIZATION:" + ur.getOrganizationId());
            }
        }

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
