package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementEntity;
import com.mannschaft.app.billing.EntitlementRepository;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.EntitlementSourceKind;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.billing.beta.dto.BetaGrantDetailResponse;
import com.mannschaft.app.billing.beta.dto.BetaGrantItem;
import com.mannschaft.app.billing.beta.dto.BetaGrantPageResponse;
import com.mannschaft.app.billing.beta.dto.EligibilityStatus;
import com.mannschaft.app.billing.beta.dto.MyBetaPerksResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * F20.3 ベータ特典: 照会（読み取り）サービス（隊2・設計書 02 §1・§4）。
 *
 * <p>シスアド一覧/詳細（{@link BetaGrantDetailResponse}）と利用者向け照会（{@link BetaGrantItem}・審査系除外）を
 * 分離して提供する。{@code validUntil}（由来 entitlements の最大 {@code valid_until}）は本サービスが
 * {@link EntitlementRepository} を横断集計して解決する（ベータ規模の N+1 は許容・Phase 2 で先読み最適化）。</p>
 *
 * <p><b>/me の eligibility（AC-N4）</b>: {@link BetaPerkEligibilityService#evaluate} を本人固定で呼び、
 * criteria 未定義（{@link BetaPerkErrorCode#CRITERIA_NOT_FOUND}）は catch して {@code null} にする
 * （NPE/404 にしない・設計仕様の唯一の例外的 catch）。</p>
 *
 * <p><b>Phase3 追補（表示名）</b>: シスアド向け一覧/詳細に {@code scopeDisplayName}/{@code grantedByName} を載せる。
 * 一覧（{@link #searchGrants}）はページ単位で {@link BetaPerkScopeNameResolver} を scopeKind ごと・
 * grantedBy ごとに <b>1 回だけ</b>呼び、per-grant では呼ばない（N+1 回避）。</p>
 */
@Service
@RequiredArgsConstructor
public class BetaGrantQueryService {

    /** 一覧 1 ページの最大件数（F20.1 SystemAdminBillingService と同じ 50 で揃える）。 */
    static final int MAX_PAGE_SIZE = 50;

    private final BetaGrantRepository betaGrantRepository;
    private final EntitlementRepository entitlementRepository;
    private final BetaPerkEligibilityService eligibilityService;
    private final BetaGrantResponseMapper mapper;
    private final BetaPerkScopeNameResolver scopeNameResolver;

    /** 現在のベータ段階（設計書 02 §3/§6・{@code mannschaft.beta.current-phase}）。 */
    @Value("${mannschaft.beta.current-phase:1}")
    private int currentPhase;

    // ============================================================
    // シスアド
    // ============================================================

    /** シスアド 付与一覧のフィルタ検索（設計書 02 §4・grantedAt 降順）。 */
    @Transactional(readOnly = true)
    public BetaGrantPageResponse searchGrants(
            Boolean reviewFlag, GrantKind grantKind, Integer betaPhase,
            EntitlementScopeKind scopeKind, Long scopeId, int page, int size) {
        int effectiveSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int effectivePage = Math.max(0, page);
        Page<BetaGrantEntity> result = betaGrantRepository.searchGrantsWithScope(
                reviewFlag, grantKind, betaPhase, scopeKind, scopeId,
                PageRequest.of(effectivePage, effectiveSize, Sort.by("grantedAt").descending()));
        List<BetaGrantEntity> grants = result.getContent();
        // Phase3 追補: 表示名はページ単位（scope種別ごと・grantedByごと）にバルク解決してから map する
        // （per-grant で Resolver を呼ぶと N+1 になる・searchGrants 側で必ず先読みする）。
        Map<EntitlementScopeKind, Map<Long, String>> scopeNames = resolveScopeDisplayNames(grants);
        Map<Long, String> grantedByNames = resolveGrantedByDisplayNames(grants);
        List<BetaGrantDetailResponse> content = grants.stream()
                .map(g -> mapper.toDetail(g, resolveValidUntil(g),
                        scopeNames.getOrDefault(g.getScopeKind(), Map.of()).get(g.getScopeId()),
                        g.getGrantedBy() == null ? null : grantedByNames.get(g.getGrantedBy())))
                .toList();
        return BetaGrantPageResponse.builder()
                .content(content)
                .page(effectivePage)
                .size(effectiveSize)
                .totalElements(result.getTotalElements())
                .build();
    }

    /** シスアド 付与詳細（不在は {@link BetaPerkErrorCode#GRANT_NOT_FOUND} 404・変更系 EP のレスポンス生成用）。 */
    @Transactional(readOnly = true)
    public BetaGrantDetailResponse getDetail(UUID grantId) {
        BetaGrantEntity grant = betaGrantRepository.findById(grantId)
                .orElseThrow(() -> new BusinessException(BetaPerkErrorCode.GRANT_NOT_FOUND));
        // 単票のため 1 件ずつだが Resolver のシグネチャは一覧と共通（Collection<Long> size=1 呼び出し）。
        String scopeDisplayName = scopeNameResolver
                .resolveScopeNames(grant.getScopeKind(), List.of(grant.getScopeId()))
                .get(grant.getScopeId());
        String grantedByName = grant.getGrantedBy() == null ? null : scopeNameResolver
                .resolveUserNames(List.of(grant.getGrantedBy()))
                .get(grant.getGrantedBy());
        return mapper.toDetail(grant, resolveValidUntil(grant), scopeDisplayName, grantedByName);
    }

    // ============================================================
    // 利用者向け（審査系除外・AC-A7）
    // ============================================================

    /** {@code GET /me/beta-perks}（本人固定・AC-A5）。eligibility は criteria 未定義なら null（AC-N4）。 */
    @Transactional(readOnly = true)
    public MyBetaPerksResponse getMyBetaPerks(Long userId) {
        List<BetaGrantItem> grants = betaGrantRepository
                .findByScopeKindAndScopeIdOrderByGrantedAtDesc(EntitlementScopeKind.USER, userId).stream()
                .map(g -> mapper.toItem(g, resolveValidUntil(g)))
                .toList();
        EligibilityStatus eligibility = evaluateSelfEligibility(userId);
        return MyBetaPerksResponse.builder()
                .grants(grants)
                .eligibility(eligibility)
                .build();
    }

    /** チーム/組織の特典照会（メンバー閲覧可・審査系除外・設計書 02 §1.2）。 */
    @Transactional(readOnly = true)
    public List<BetaGrantItem> getScopeBetaPerks(EntitlementScopeKind scopeKind, Long scopeId) {
        return betaGrantRepository
                .findByScopeKindAndScopeIdOrderByGrantedAtDesc(scopeKind, scopeId).stream()
                .map(g -> mapper.toItem(g, resolveValidUntil(g)))
                .toList();
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    /**
     * 現行フェーズの本人充足状況を評価する（AC-N4）。criteria 未定義/enabled=false は
     * {@link BetaPerkErrorCode#CRITERIA_NOT_FOUND} を catch して null（設計仕様の例外的 catch・症状隠蔽ではない）。
     */
    private EligibilityStatus evaluateSelfEligibility(Long userId) {
        try {
            EligibilityResult result = eligibilityService.evaluate(
                    GrantKind.INDIVIDUAL, EntitlementScopeKind.USER, userId, currentPhase);
            return mapper.toEligibilityStatus(result);
        } catch (BusinessException ex) {
            if (ex.getErrorCode() == BetaPerkErrorCode.CRITERIA_NOT_FOUND) {
                return null; // 現行フェーズに criteria 未定義＝進捗開示なし（AC-N4）。
            }
            throw ex;
        }
    }

    /**
     * Phase3 追補: ページ内の grant を scopeKind ごとにグルーピングし、種別ごと <b>1 クエリ</b>で
     * スコープ表示名を先読みする（N+1 回避）。scopeId は scopeKind をまたいで衝突しうる（例: TEAM id=5 と
     * ORG id=5）ため、{@link EntitlementScopeKind} をキーに持つネスト Map で区別する。
     */
    private Map<EntitlementScopeKind, Map<Long, String>> resolveScopeDisplayNames(List<BetaGrantEntity> grants) {
        Map<EntitlementScopeKind, Map<Long, String>> byKind = new EnumMap<>(EntitlementScopeKind.class);
        for (EntitlementScopeKind kind : EntitlementScopeKind.values()) {
            List<Long> ids = grants.stream()
                    .filter(g -> g.getScopeKind() == kind)
                    .map(BetaGrantEntity::getScopeId)
                    .distinct()
                    .toList();
            if (!ids.isEmpty()) {
                byKind.put(kind, scopeNameResolver.resolveScopeNames(kind, ids));
            }
        }
        return byKind;
    }

    /** Phase3 追補: ページ内の grantedBy（付与操作者 userId・null=SYSTEM は除外）を <b>1 クエリ</b>で先読みする。 */
    private Map<Long, String> resolveGrantedByDisplayNames(List<BetaGrantEntity> grants) {
        List<Long> operatorIds = grants.stream()
                .map(BetaGrantEntity::getGrantedBy)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        return operatorIds.isEmpty() ? Map.of() : scopeNameResolver.resolveUserNames(operatorIds);
    }

    /**
     * 由来 entitlements（未取消）の最大 {@code valid_until} を返す（INDIVIDUAL は全 null ゆえ null）。
     * ベータ規模の N+1 は許容（Phase 2 で source_ref_id 一括先読みに最適化）。
     */
    private LocalDateTime resolveValidUntil(BetaGrantEntity grant) {
        List<EntitlementEntity> rows = entitlementRepository
                .findBySourceKindAndSourceRefIdAndRevokedAtIsNull(EntitlementSourceKind.BETA_GRANT, grant.getId());
        return rows.stream()
                .map(EntitlementEntity::getValidUntil)
                .filter(v -> v != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }
}
