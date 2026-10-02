package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;
import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionReason;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 加盟の申請・招待の再送制限（拒否後の冷却・ブロック・連打防止）の判定と記録（F01.2.1 §5.4）。
 *
 * <p>申請（2-B1）・取下げ（2-B1）のほか、拒否（2-B2）・招待の拒否と取消（2-C）が同じ記録経路を使う共通部品。</p>
 *
 * <h2>記録（UPSERT）と合成</h2>
 * <p>(組織, チーム, 向き) の UNIQUE 制約により制限は1行に収束する。記録は次の2段で行う。</p>
 * <ol>
 *   <li>{@code INSERT ... ON DUPLICATE KEY UPDATE id = id} で行を<b>無ければ作る</b>。並行して2本が走っても
 *       片方が作り、もう片方は何もしない（UNIQUE 違反を例外にしないので、呼び出し側のトランザクションを
 *       rollback-only にしない。AC-G135）。</li>
 *   <li>その行を {@code FOR UPDATE} で取り、{@link TeamOrgAffiliationRestrictionComposer} の合成規則
 *       （BLOCK は COOLDOWN で上書きしない・COOLDOWN どうしは期限の遅いほうを残す）で置き換えるか決める。
 *       行ロックにより、合成の読み書きが並行しても結果が一意に定まる。</li>
 * </ol>
 *
 * <h2>時刻</h2>
 * <p>制限の期限は「起きた瞬間」から決まる {@link Instant}（UTC）で持つ。現在時刻は {@link Clock} から取り、
 * 判定にも同じ値を引数として渡す（SQL の {@code NOW()} を判定に使わない。§4.6）。
 * IT では Clock を固定して時間を進める。</p>
 */
@Service
public class TeamOrgAffiliationRestrictionService {

    private final TeamOrgAffiliationRestrictionRepository repository;
    private final Clock clock;
    private final Duration resendCooldown;

    public TeamOrgAffiliationRestrictionService(
            TeamOrgAffiliationRestrictionRepository repository,
            Clock clock,
            @Value("${mannschaft.affiliation.resend-cooldown-hours:24}") long resendCooldownHours) {
        this.repository = repository;
        this.clock = clock;
        this.resendCooldown = Duration.ofHours(resendCooldownHours);
    }

    /**
     * 指定の向きで、いま有効な制限（BLOCK、または期限が未来の COOLDOWN）があるかを返す。
     * 期限切れの COOLDOWN は無視する（物理削除は夜間バッチ）。
     */
    public boolean isRestricted(Long organizationId, Long teamId, TeamOrgAffiliationDirection direction) {
        return repository.countActive(organizationId, teamId, direction,
                TeamOrgAffiliationRestrictionKind.BLOCK, Instant.now(clock)) > 0;
    }

    /**
     * 有効な制限があれば 403 {@code TEAM_068} を投げる。冷却かブロックかは区別しない（相手に見せない。§5.4）。
     */
    public void assertNotRestricted(Long organizationId, Long teamId, TeamOrgAffiliationDirection direction) {
        if (isRestricted(organizationId, teamId, direction)) {
            throw new BusinessException(TeamErrorCode.TEAM_068);
        }
    }

    /**
     * 申請の取下げによる連打防止の制限を記録する（TEAM_APPLY・WITHDRAWN・COOLDOWN 24時間。§6.4 step 3）。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordWithdrawal(Long organizationId, Long teamId, Long createdBy) {
        record(organizationId, teamId, TeamOrgAffiliationDirection.TEAM_APPLY,
                TeamOrgAffiliationRestrictionReason.WITHDRAWN,
                TeamOrgAffiliationRestrictionKind.COOLDOWN, resendCooldown, createdBy);
    }

    /**
     * 制限を UPSERT する（合成規則つき）。
     *
     * @param kind     COOLDOWN なら {@code cooldown} 後まで、BLOCK なら無期限
     * @param cooldown COOLDOWN の長さ（BLOCK では使わない）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long organizationId, Long teamId, TeamOrgAffiliationDirection direction,
                       TeamOrgAffiliationRestrictionReason reason, TeamOrgAffiliationRestrictionKind kind,
                       Duration cooldown, Long createdBy) {
        Instant until = null;
        if (kind == TeamOrgAffiliationRestrictionKind.COOLDOWN) {
            // DATETIME は秒精度で保存されるため、ここで秒に丸めておく（行を作った直後の合成が「期限が延びた」と誤判定しない）
            until = Instant.now(clock).plus(cooldown).truncatedTo(ChronoUnit.SECONDS);
            repository.insertCooldownIfAbsent(UuidV7.generate(), organizationId, teamId, direction.name(),
                    reason.name(), until.getEpochSecond(), createdBy);
        } else {
            repository.insertBlockIfAbsent(UuidV7.generate(), organizationId, teamId, direction.name(),
                    reason.name(), createdBy);
        }

        TeamOrgAffiliationRestrictionEntity row = repository
                .findForUpdate(organizationId, teamId, direction)
                .orElseThrow(() -> new IllegalStateException(
                        "制限行の作成直後に読み出せない: organizationId=" + organizationId
                                + " teamId=" + teamId + " direction=" + direction));
        if (TeamOrgAffiliationRestrictionComposer.incomingWins(
                row.getKind(), row.getRestrictedUntil(), kind, until)) {
            row.replaceWith(kind, until, reason, createdBy);
        }
    }
}
