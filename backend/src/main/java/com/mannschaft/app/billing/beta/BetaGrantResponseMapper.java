package com.mannschaft.app.billing.beta;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.billing.beta.dto.BetaGrantDetailResponse;
import com.mannschaft.app.billing.beta.dto.BetaGrantItem;
import com.mannschaft.app.billing.beta.dto.EligibilityStatus;
import com.mannschaft.app.billing.beta.dto.MetricProgressDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * F20.3 ベータ特典: {@link BetaGrantEntity} → API DTO のマッピング（隊2・設計書 02 §1/§4）。
 *
 * <p><b>秘匿の分離（03 §3・AC-A7）</b>: 利用者向け {@link #toItem} は審査系フィールド
 * （{@code review_flag}/{@code review_reason}/{@code criteria_snapshot}）を<b>写さない</b>。
 * シスアド向け {@link #toDetail} はそれらを含む。DTO 型自体を分けることでレスポンス漏洩を型で封じる。</p>
 *
 * <p>{@code validUntil}（由来 entitlements の最大 valid_until）は本マッパーでは解決できない
 * （billing.beta が entitlements を横断集計するのは呼び出し側 {@link BetaGrantQueryService} の責務）ため、
 * 呼び出し側が算出して渡す。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BetaGrantResponseMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    /** 利用者向け項目へマップ（審査系は除外・AC-A7）。 */
    public BetaGrantItem toItem(BetaGrantEntity grant, LocalDateTime validUntil) {
        return BetaGrantItem.builder()
                .grantId(grant.getId().toString())
                .betaPhase(grant.getBetaPhase())
                .grantKind(grant.getGrantKind().name())
                .grantedAt(grant.getGrantedAt())
                .validUntil(validUntil)
                .revokedAt(grant.getRevokedAt())
                .featureKeys(parseFeatureKeys(grant.getGrantedFeatureKeys()))
                .activeMemberCountSnapshot(grant.getActiveMemberCountSnapshot())
                .build();
    }

    /** シスアド向け詳細へマップ（審査系・criteria_snapshot を含む）。 */
    public BetaGrantDetailResponse toDetail(BetaGrantEntity grant, LocalDateTime validUntil) {
        return BetaGrantDetailResponse.builder()
                .grantId(grant.getId().toString())
                .betaPhase(grant.getBetaPhase())
                .grantKind(grant.getGrantKind().name())
                .scopeKind(grant.getScopeKind().name())
                .scopeId(grant.getScopeId())
                .organizationId(grant.getOrganizationId())
                .grantedAt(grant.getGrantedAt())
                .validUntil(validUntil)
                .featureKeys(parseFeatureKeys(grant.getGrantedFeatureKeys()))
                .activeMemberCountSnapshot(grant.getActiveMemberCountSnapshot())
                .criteriaSnapshot(parseSnapshot(grant.getCriteriaSnapshot()))
                .reviewFlag(grant.isReviewFlag())
                .reviewReason(grant.getReviewReason() == null ? null : grant.getReviewReason().name())
                .reviewFlaggedAt(grant.getReviewFlaggedAt())
                .reviewResolvedAt(grant.getReviewResolvedAt())
                .revokedAt(grant.getRevokedAt())
                .revokeReason(grant.getRevokeReason() == null ? null : grant.getRevokeReason().name())
                .grantedBy(grant.getGrantedBy())
                .build();
    }

    /** 内部評価結果 → API 充足状況（{@code /me} eligibility）。 */
    public EligibilityStatus toEligibilityStatus(EligibilityResult result) {
        List<MetricProgressDto> metrics = result.metrics().stream()
                .map(m -> MetricProgressDto.builder()
                        .metricKey(m.metricKey())
                        .actual(m.actual())
                        .required(m.required())
                        .build())
                .toList();
        return EligibilityStatus.builder()
                .betaPhase(result.betaPhase())
                .eligible(result.eligible())
                .metrics(metrics)
                .build();
    }

    /** 内部指標 → API 指標 DTO（候補一覧用）。 */
    public MetricProgressDto toMetricDto(MetricProgress m) {
        return MetricProgressDto.builder()
                .metricKey(m.metricKey())
                .actual(m.actual())
                .required(m.required())
                .build();
    }

    private List<String> parseFeatureKeys(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ex) {
            // granted_feature_keys は NOT NULL の JSON 配列。壊れていれば握り潰さず WARN で可視化し空配列で返す。
            log.warn("granted_feature_keys の JSON パースに失敗しました json={}", json, ex);
            return Collections.emptyList();
        }
    }

    private Object parseSnapshot(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception ex) {
            log.warn("criteria_snapshot の JSON パースに失敗しました json={}", json, ex);
            return null;
        }
    }
}
