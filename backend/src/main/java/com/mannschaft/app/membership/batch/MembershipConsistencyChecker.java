package com.mannschaft.app.membership.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.dto.UserRoleOnlyDiffRow;
import com.mannschaft.app.role.service.RoleService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
 * <p>差分件数は {@link MembershipRepository#countOnlyInMemberships()} /
 * {@link RoleService#countUserRolesOnlyDiff()} で SQL 側（{@code NOT EXISTS} 相関サブクエリ）に
 * 集計させ、アプリはスカラー件数のみを受け取る。両テーブルの全件をヒープへロードして突き合わせる
 * 実装では行数に比例してメモリを消費するため、行数が増えてもヒープが膨らまないこの形へ改めた。
 * user_roles 側（role ドメイン）へは {@link RoleService} 経由でのみアクセスし、role ドメインの
 * Repository を membership ドメインから直接参照しない（モジュラーモノリスのドメイン境界原則）。</p>
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
    private final RoleService roleService;
    private final MeterRegistry meterRegistry;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。memberships と user_roles の整合性検査であり、検知のみで再開後に同じ差分を検出し直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "membership-consistency-check-daily", description = "memberships と user_roles の整合性を毎日 04:00 に検査する")
    @Scheduled(cron = "0 0 4 * * *")
    // 起動間隔は日次 04:00。差分件数は DB 側の NOT EXISTS 相関サブクエリで集計するため、
    // 行数が増えてもアプリ側のヒープは膨らまない。将来のデータ増を見込み 30 分を上限とする。
    @SchedulerLock(name = "membershipConsistencyCheckDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void checkConsistency() {
        // --- 既存メトリクス: 対称差の総件数 ---
        long onlyInMembershipsCount = membershipRepository.countOnlyInMemberships();
        long onlyInUserRolesCount = roleService.countUserRolesOnlyDiff();
        long diffCount = onlyInMembershipsCount + onlyInUserRolesCount;
        meterRegistry.gauge("f005.consistency.diff.count", diffCount);

        // --- 新設メトリクス: user_roles のみに存在する件数（write-path 移行漏れ兆候） ---
        meterRegistry.gauge("f005.consistency.only_in_user_roles.count", onlyInUserRolesCount);

        if (diffCount == 0) {
            log.info("F00.5 整合性チェック: 差分なし（整合性 OK）");
            return;
        }

        log.warn("F00.5 整合性チェック: memberships と user_roles の差分あり。差分件数={}", diffCount);
        log.warn("F00.5 差分詳細: memberships のみ={} 件, user_roles のみ={} 件",
                onlyInMembershipsCount, onlyInUserRolesCount);

        // onlyInUserRoles > 0 は F00.5 write-path 移行漏れの再発兆候 → ERROR レベルで記録
        if (onlyInUserRolesCount > 0) {
            log.error("[F00.5][ALERT] user_roles にあるが memberships にアクティブ行が無いユーザーを検出。" +
                    "件数={} （write-path 移行漏れの可能性あり。対象ユーザーが 403 で締め出されるリスクがある）",
                    onlyInUserRolesCount);
            List<UserRoleOnlyDiffRow> samples =
                    roleService.sampleUserRolesOnlyDiff(PageRequest.of(0, SAMPLE_LOG_LIMIT));
            for (UserRoleOnlyDiffRow row : samples) {
                log.error("[F00.5][ALERT] 欠落サンプル: userId={}, scopeType={}, scopeId={}",
                        row.userId(), row.scopeType(), row.scopeId());
            }
            if (onlyInUserRolesCount > SAMPLE_LOG_LIMIT) {
                log.error("[F00.5][ALERT] ... 他 {} 件（ログ省略。全件は DB クエリで確認すること）",
                        onlyInUserRolesCount - SAMPLE_LOG_LIMIT);
            }
        }
    }
}
