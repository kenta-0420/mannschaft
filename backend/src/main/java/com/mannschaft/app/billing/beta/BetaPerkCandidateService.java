package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.beta.dto.BetaPerkCandidateResponse;
import com.mannschaft.app.billing.beta.dto.MetricProgressDto;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * F20.3 ベータ特典: 付与候補 dry-run サービス（隊2・設計書 02 §4.5）。
 *
 * <p>未付与かつ充足のスコープを抽出する（<b>付与はしない</b>・TEAM_ORG の審査前スクリーニング用）。</p>
 *
 * <p><b>Phase 1 の割り切り（設計書 02 §4.5・§4.7 最小要件）</b>:</p>
 * <ul>
 *   <li>対象走査は {@link TeamRepository#findActiveTeamIdsForBeta} / {@link OrganizationRepository#findActiveOrgIdsForBeta}
 *       のページ内スコープに対し {@link BetaPerkEligibilityService#evaluate} を素直に適用する
 *       （評価はスコープごと＝ページ内 N 回）。<b>Phase 2 で {@code page_view_logs} の GROUP BY 先読み
 *       （AC-P1/P2）に最適化</b>し、自動付与バッチと同一の一括評価に寄せる。</li>
 *   <li><b>INDIVIDUAL は空リストを返す</b>: 個人特典の候補発見は Phase 2 の自動付与バッチ（設計書 02 §3）の
 *       責務であり、全ユーザー表の走査を dry-run 画面で行わない。dry-run 画面が対象とするのは
 *       TEAM_ORG（審査前スクリーニング・§4.5）。</li>
 *   <li>クロスドメインは {@code MembershipQueryService} と同型に <b>scalar（{@code Long} ID）</b>と
 *       名前 Map のみを他ドメイン Repository から取得し、他ドメイン Entity を import しない（D-1 回避）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class BetaPerkCandidateService {

    /** 候補 dry-run 1 ページの最大件数（重い評価走査を保護）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final BetaGrantRepository betaGrantRepository;
    private final BetaPerkEligibilityService eligibilityService;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final BetaGrantResponseMapper mapper;

    /**
     * 付与候補を dry-run 抽出する（設計書 02 §4.5）。
     *
     * @param grantKind INDIVIDUAL（空を返す・上記割り切り）/ TEAM_ORG（team+org を走査）
     * @param betaPhase ベータ段階（1〜4）
     * @param page      ページ番号（team・org それぞれの母集団に対する窓・Phase 2 で統合予定）
     * @param size      ページサイズ
     */
    // 越境tx番人(D-3)回避のため @Transactional を付けない。読み取り専用の dry-run 走査で
    // 他ドメイン(team/org)Repository から scalar(ID)/名前Map のみ取得し、遅延ロードも
    // クエリ間整合要求も無いため単一txは不要（MembershipQueryService/LoginActivityQueryService と同型）。
    public List<BetaPerkCandidateResponse> findCandidates(
            GrantKind grantKind, int betaPhase, int page, int size) {
        if (betaPhase < 1 || betaPhase > 4) {
            throw new BusinessException(BetaPerkErrorCode.BETA_PHASE_INVALID);
        }
        if (grantKind == GrantKind.INDIVIDUAL) {
            // 個人候補の発見は Phase 2 自動付与バッチの責務（設計書 02 §3・上記割り切り）。
            return List.of();
        }
        int effectiveSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int effectivePage = Math.max(0, page);
        PageRequest pageable = PageRequest.of(effectivePage, effectiveSize);

        try {
            List<BetaPerkCandidateResponse> out = new ArrayList<>();
            out.addAll(collect(EntitlementScopeKind.TEAM, betaPhase, grantKind,
                    teamRepository.findActiveTeamIdsForBeta(pageable), teamRepository::findNameMapByIdIn));
            out.addAll(collect(EntitlementScopeKind.ORG, betaPhase, grantKind,
                    organizationRepository.findActiveOrgIdsForBeta(pageable),
                    organizationRepository::findNameMapByIdIn));
            return out;
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == BetaPerkErrorCode.CRITERIA_NOT_FOUND) {
                // 当該フェーズ×種別の criteria 未定義＝候補ゼロ（付与不可の状態）。
                return List.of();
            }
            throw ex;
        }
    }

    private List<BetaPerkCandidateResponse> collect(
            EntitlementScopeKind scopeKind, int betaPhase, GrantKind grantKind,
            Page<Long> ids, java.util.function.Function<List<Long>, Map<Long, String>> nameResolver) {
        List<Long> content = ids.getContent();
        if (content.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = nameResolver.apply(content);
        List<BetaPerkCandidateResponse> result = new ArrayList<>();
        for (Long scopeId : content) {
            // 既に当該フェーズで付与済み（取消済み含む）は候補から除外。
            if (betaGrantRepository.findByScopeKindAndScopeIdAndBetaPhase(scopeKind, scopeId, betaPhase).isPresent()) {
                continue;
            }
            EligibilityResult r = eligibilityService.evaluate(grantKind, scopeKind, scopeId, betaPhase);
            if (!r.eligible()) {
                continue;
            }
            List<MetricProgressDto> metrics = r.metrics().stream().map(mapper::toMetricDto).toList();
            result.add(BetaPerkCandidateResponse.builder()
                    .scopeKind(scopeKind.name())
                    .scopeId(scopeId)
                    .displayName(names.get(scopeId))
                    .metrics(metrics)
                    .build());
        }
        return result;
    }
}
