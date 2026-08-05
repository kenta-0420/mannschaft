package com.mannschaft.app.membership.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F00.5 フェーズ 3 — memberships / user_roles 整合性チェックバッチ。
 *
 * <p>毎日 AM4 時に memberships テーブルのアクティブ行（MEMBER/SUPPORTER）と
 * user_roles テーブルの MEMBER/SUPPORTER 行を比較し、差分件数を
 * ログ・Micrometer メトリクスに記録する。</p>
 *
 * <h2>メトリクス一覧</h2>
 * <ul>
 *   <li>{@code f005.consistency.diff.count} — 対称差の総件数（既存）</li>
 *   <li>{@code f005.consistency.only_in_user_roles.count} — user_roles にあるが memberships に
 *       アクティブ行が存在しない件数（新設）。0 より大きい場合は F00.5 write-path 移行漏れの
 *       再発兆候であり、対象ユーザーが 403 で締め出されるリスクがある。</li>
 * </ul>
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

    /** onlyInUserRoles のサンプルログ出力件数上限。大量ヒット時のログ氾濫を防ぐ。 */
    static final int SAMPLE_LOG_LIMIT = 10;

    private final MembershipRepository membershipRepository;
    private final UserRoleRepository userRoleRepository;
    private final MeterRegistry meterRegistry;

    @BatchEndpoint(name = "membership-consistency-check-daily", description = "memberships と user_roles の整合性を毎日 04:00 に検査する")
    @Scheduled(cron = "0 0 4 * * *")
    // 起動間隔は日次 04:00。memberships と user_roles の全件突き合わせで、行数に比例するが集合演算 1 往復のため最悪でも数分。
    // 将来のデータ増を見込み 30 分を上限とする。
    @SchedulerLock(name = "membershipConsistencyCheckDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void checkConsistency() {
        DiffResult result = computeDiffResult();

        // --- 既存メトリクス: 対称差の総件数 ---
        long diffCount = result.onlyInMemberships().size() + result.onlyInUserRoles().size();
        meterRegistry.gauge("f005.consistency.diff.count", diffCount);

        // --- 新設メトリクス: user_roles のみに存在する件数（write-path 移行漏れ兆候） ---
        long onlyInUserRolesCount = result.onlyInUserRoles().size();
        meterRegistry.gauge("f005.consistency.only_in_user_roles.count", onlyInUserRolesCount);

        if (diffCount == 0) {
            log.info("F00.5 整合性チェック: 差分なし（整合性 OK）");
            return;
        }

        log.warn("F00.5 整合性チェック: memberships と user_roles の差分あり。差分件数={}", diffCount);
        log.warn("F00.5 差分詳細: memberships のみ={} 件, user_roles のみ={} 件",
                result.onlyInMemberships().size(), onlyInUserRolesCount);

        // onlyInUserRoles > 0 は F00.5 write-path 移行漏れの再発兆候 → ERROR レベルで記録
        if (onlyInUserRolesCount > 0) {
            log.error("[F00.5][ALERT] user_roles にあるが memberships にアクティブ行が無いユーザーを検出。" +
                    "件数={} （write-path 移行漏れの可能性あり。対象ユーザーが 403 で締め出されるリスクがある）",
                    onlyInUserRolesCount);
            List<String> samples = result.onlyInUserRoles().stream()
                    .limit(SAMPLE_LOG_LIMIT)
                    .toList();
            for (String key : samples) {
                // key 形式: "userId:scopeType:scopeId"
                String[] parts = key.split(":", 3);
                if (parts.length == 3) {
                    log.error("[F00.5][ALERT] 欠落サンプル: userId={}, scopeType={}, scopeId={}",
                            parts[0], parts[1], parts[2]);
                }
            }
            if (onlyInUserRolesCount > SAMPLE_LOG_LIMIT) {
                log.error("[F00.5][ALERT] ... 他 {} 件（ログ省略。全件は DB クエリで確認すること）",
                        onlyInUserRolesCount - SAMPLE_LOG_LIMIT);
            }
        }
    }

    /**
     * memberships のアクティブ行（left_at IS NULL）と user_roles の TEAM/ORGANIZATION 行を比較し、
     * 対称差の件数（userId × scopeType × scopeId の組み合わせ）を返す。
     *
     * <p>後方互換のため既存シグネチャを維持する。内部で {@link #computeDiffResult()} を呼ぶ。</p>
     *
     * @return 対称差の総件数
     */
    long computeDiff() {
        DiffResult result = computeDiffResult();
        return result.onlyInMemberships().size() + result.onlyInUserRoles().size();
    }

    /**
     * memberships のアクティブ行と user_roles 行を突き合わせ、差分を {@link DiffResult} で返す。
     *
     * <p>対称差 = (memberships のみに存在する組み合わせ) + (user_roles のみに存在する組み合わせ)</p>
     */
    DiffResult computeDiffResult() {
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

        // 対称差: user_roles にのみある組み合わせ（F00.5 write-path 移行漏れの兆候）
        Set<String> onlyInUserRoles = new HashSet<>(userRoleKeys);
        onlyInUserRoles.removeAll(membershipKeys);

        return new DiffResult(onlyInMemberships, onlyInUserRoles);
    }

    /**
     * 整合性チェックの差分結果。
     *
     * @param onlyInMemberships memberships にのみ存在するキー集合（userId:scopeType:scopeId 形式）
     * @param onlyInUserRoles   user_roles にのみ存在するキー集合（userId:scopeType:scopeId 形式）。
     *                          0 より大きい場合は F00.5 write-path 移行漏れの再発兆候。
     */
    record DiffResult(Set<String> onlyInMemberships, Set<String> onlyInUserRoles) {
        DiffResult {
            // 防御的コピー（不変性を保証）
            onlyInMemberships = new HashSet<>(onlyInMemberships);
            onlyInUserRoles = new HashSet<>(onlyInUserRoles);
        }
    }
}
