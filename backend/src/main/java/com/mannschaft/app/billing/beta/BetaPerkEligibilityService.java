package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.ScopeMemberCountService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * F20.3 ベータ特典: 付与条件（活動実績）の評価サービス（設計書 02 §2・README §2）。
 *
 * <p><b>判定規則</b>: {@code beta_perk_criteria}（{@code beta_phase} × {@code grant_kind}）の
 * <b>非 NULL の指標だけを AND 評価</b>し、境界は<b>「以上」</b>（{@code actual >= required}）。
 * 指標が 1 つも定義されない（＝全 NULL）criteria はマスタ CRUD 側で保存不可（{@code BETA_PERK_009}・
 * 隊2 の責務）。criteria 未定義 / {@code enabled=false} は {@link BetaPerkErrorCode#CRITERIA_NOT_FOUND}
 * （404・NPE にしない・AC-N4）。</p>
 *
 * <p><b>指標の計測源</b>:</p>
 * <ul>
 *   <li>{@code activeDays} — {@link LoginActivityQueryService}（{@code audit_logs} の LOGIN_SUCCESS・
 *       INDIVIDUAL のみ意味を持つ・README §7）。</li>
 *   <li>{@code membershipTenureDays} — {@link MembershipQueryService}（INDIVIDUAL=最古有効所属 /
 *       TEAM_ORG=スコープ作成日）。</li>
 *   <li>{@code activeMembers}（TEAM_ORG のみ） — F20.1 {@link ScopeMemberCountService#countActiveMembers}
 *       を再利用（{@code countActiveDistinctUsersByScope}・新規メソッドを足さない）。</li>
 * </ul>
 *
 * <p><b>キャッシュ</b>: {@code betaPerk:eligibility}（Valkey・TTL 10 分・{@link com.mannschaft.app.config.RedisConfig}）。
 * enum キーは {@code name()} で String 化する（memory {@code feedback_cacheable_enum_key_redis}）。
 * {@code entitlement:check}（60 秒）とは別キャッシュ。例外（CRITERIA_NOT_FOUND）は Spring がキャッシュしない。</p>
 *
 * <p><b>Clock</b>: {@link Clock} を注入し（{@code EntitlementQueryService} と同型）、テストで固定 Clock に
 * 差し替えて評価ウィンドウ・在籍日数の境界（AC-B1/B2）を決定論的に検証できるようにする。</p>
 */
@Service
@RequiredArgsConstructor
public class BetaPerkEligibilityService {

    private final BetaPerkCriteriaRepository criteriaRepository;
    private final LoginActivityQueryService loginActivityQueryService;
    private final MembershipQueryService membershipQueryService;
    private final ScopeMemberCountService scopeMemberCountService;
    private final Clock clock;

    /**
     * 付与条件を評価する（設計書 02 §2）。
     *
     * @param grantKind INDIVIDUAL / TEAM_ORG
     * @param scopeKind USER / TEAM / ORG
     * @param scopeId   users.id / teams.id / organizations.id（INDIVIDUAL の activeDays は本値＝userId）
     * @param betaPhase ベータ段階（1〜4）
     * @return 評価結果（定義済み指標の進捗と eligible）
     * @throws BusinessException {@link BetaPerkErrorCode#CRITERIA_NOT_FOUND}（未定義 / enabled=false）
     */
    @Cacheable(value = "betaPerk:eligibility",
            key = "#grantKind.name() + ':' + #scopeKind.name() + ':' + #scopeId + ':' + #betaPhase")
    public EligibilityResult evaluate(
            GrantKind grantKind, EntitlementScopeKind scopeKind, Long scopeId, int betaPhase) {

        BetaPerkCriteriaEntity criteria = criteriaRepository
                .findById(new BetaPerkCriteriaId(betaPhase, grantKind))
                .filter(BetaPerkCriteriaEntity::isEnabled)
                .orElseThrow(() -> new BusinessException(BetaPerkErrorCode.CRITERIA_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);
        List<MetricProgress> metrics = new ArrayList<>();

        // activeDays: INDIVIDUAL（本人ログイン日数）のみ計測可能（TEAM_ORG は USER を持たない・README §7）。
        if (grantKind == GrantKind.INDIVIDUAL && criteria.getMinActiveDays() != null) {
            long actual = loginActivityQueryService.countDistinctActiveDays(
                    scopeId, now.minusDays(criteria.getEvaluationWindowDays()));
            metrics.add(new MetricProgress("activeDays", actual, criteria.getMinActiveDays()));
        }

        // membershipTenureDays: INDIVIDUAL=最古有効所属 / TEAM_ORG=スコープ作成日。
        if (criteria.getMinMembershipTenureDays() != null) {
            long actual = membershipQueryService.tenureDays(scopeKind, scopeId, now);
            metrics.add(new MetricProgress("membershipTenureDays", actual, criteria.getMinMembershipTenureDays()));
        }

        // activeMembers: TEAM_ORG のみ意味を持つ（F20.1 と同一定義）。
        if (grantKind == GrantKind.TEAM_ORG && criteria.getMinActiveMembers() != null) {
            long actual = scopeMemberCountService.countActiveMembers(scopeKind, scopeId);
            metrics.add(new MetricProgress("activeMembers", actual, criteria.getMinActiveMembers()));
        }

        boolean eligible = metrics.stream().allMatch(MetricProgress::met);
        return new EligibilityResult(eligible, metrics, betaPhase, criteria.getEvaluationWindowDays());
    }
}
