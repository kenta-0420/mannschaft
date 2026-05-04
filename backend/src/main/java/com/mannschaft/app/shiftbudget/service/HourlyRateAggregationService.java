package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.dto.PositionBreakdown;
import com.mannschaft.app.shiftbudget.dto.PositionRequiredCount;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 平均時給算出サービス（3 モード対応）。
 *
 * <p>設計書 F08.7 §4.1 のロジックに準拠する:</p>
 * <ul>
 *   <li>{@link RateMode#MEMBER_AVG}: チーム全アクティブメンバーの単純平均</li>
 *   <li>{@link RateMode#POSITION_AVG}: ポジション別平均 × 必要人数の加重平均
 *       <br>(Phase 9-α 暫定: ポジション別メンバー区分が未実装のためチーム全体の平均にフォールバック)</li>
 *   <li>{@link RateMode#EXPLICIT}: 呼び出し側指定値をそのまま採用</li>
 * </ul>
 *
 * <p>境界ケース warning:</p>
 * <ul>
 *   <li>{@code AVG_RATE_ZERO} — 平均時給が 0</li>
 *   <li>{@code INSUFFICIENT_RATE_DATA} — MEMBER_AVG で時給設定済メンバーが過半数未満</li>
 *   <li>{@code POSITION_NO_RATE_DATA} — POSITION_AVG で当該ポジションに時給設定者 0 人</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HourlyRateAggregationService {

    private final ShiftBudgetRateQueryRepository rateQueryRepository;

    /**
     * 集計結果。
     *
     * @param avgHourlyRate     算出された平均時給 (BigDecimal)
     * @param warnings          検出された境界ケース警告のリスト
     * @param positionBreakdown POSITION_AVG モード時のみ非 null
     */
    public record AggregationResult(
            BigDecimal avgHourlyRate,
            List<String> warnings,
            List<PositionBreakdown> positionBreakdown
    ) {
    }

    /**
     * 3 モードを切り替えて平均時給を算出する。
     *
     * @param request リクエスト DTO（バリデーション済を前提）
     * @return 集計結果
     */
    public AggregationResult aggregate(RequiredSlotsRequest request) {
        return switch (request.rateMode()) {
            case EXPLICIT -> aggregateExplicit(request);
            case MEMBER_AVG -> aggregateMemberAvg(request);
            case POSITION_AVG -> aggregatePositionAvg(request);
        };
    }

    // ----------------------------------------------------------------
    // EXPLICIT
    // ----------------------------------------------------------------

    private AggregationResult aggregateExplicit(RequiredSlotsRequest request) {
        if (request.avgHourlyRate() == null) {
            throw new BusinessException(ShiftBudgetErrorCode.MISSING_EXPLICIT_RATE);
        }
        BigDecimal rate = request.avgHourlyRate();
        List<String> warnings = new ArrayList<>();
        if (rate.compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("AVG_RATE_ZERO");
        }
        return new AggregationResult(rate, warnings, null);
    }

    // ----------------------------------------------------------------
    // MEMBER_AVG
    // ----------------------------------------------------------------

    private AggregationResult aggregateMemberAvg(RequiredSlotsRequest request) {
        if (request.teamId() == null) {
            throw new BusinessException(ShiftBudgetErrorCode.TEAM_NOT_FOUND);
        }
        List<Object[]> rows = rateQueryRepository.findTeamAverageRate(
                request.teamId(), LocalDate.now());

        BigDecimal avgRate = BigDecimal.ZERO;
        long memberCount = 0;
        if (!rows.isEmpty() && rows.get(0)[0] != null) {
            avgRate = new BigDecimal(rows.get(0)[0].toString())
                    .setScale(2, RoundingMode.HALF_UP);
            memberCount = ((Number) rows.get(0)[1]).longValue();
        }

        List<String> warnings = new ArrayList<>();
        if (avgRate.compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("AVG_RATE_ZERO");
        }
        // 設計書 §4.1 「平均時給未設定メンバーが過半数を占める場合」 — INSUFFICIENT_RATE_DATA
        // 過半数判定は team_members テーブル前提で、F08.7 9-α/β/γ/δ スコープ外の F00 基盤再設計で導入予定。
        // 現状は「時給設定済 0 人」の場合のみ警告を発する暫定実装で運用継続する
        // （F00 で team_members 新設後に厳密化する。本軍では着手しない）。
        if (memberCount == 0) {
            warnings.add("INSUFFICIENT_RATE_DATA");
        }
        return new AggregationResult(avgRate, warnings, null);
    }

    // ----------------------------------------------------------------
    // POSITION_AVG
    // ----------------------------------------------------------------

    private AggregationResult aggregatePositionAvg(RequiredSlotsRequest request) {
        if (request.teamId() == null) {
            throw new BusinessException(ShiftBudgetErrorCode.TEAM_NOT_FOUND);
        }
        List<PositionRequiredCount> positions = request.positionRequiredCounts();
        if (positions == null || positions.isEmpty()) {
            throw new BusinessException(ShiftBudgetErrorCode.MISSING_POSITION_COUNTS);
        }

        // バリデーション: 重複 position_id
        Set<Long> seen = new HashSet<>();
        for (PositionRequiredCount p : positions) {
            if (!seen.add(p.positionId())) {
                throw new BusinessException(ShiftBudgetErrorCode.DUPLICATE_POSITION_ID);
            }
            // バリデーション: required_count <= 0 は @Positive で 400 になるが、
            // null チェックは @NotNull で済む。ここでは追加チェック不要。
        }

        // 全ポジションがチームに紐付いているかを検証（IDOR 対策）
        for (PositionRequiredCount p : positions) {
            long count = rateQueryRepository.countPositionInTeam(p.positionId(), request.teamId());
            if (count == 0) {
                throw new BusinessException(ShiftBudgetErrorCode.TEAM_NOT_FOUND);
            }
        }

        // 加重平均計算
        // 設計書 §4.1: weighted_avg = Σ(position_avg × required_count) ÷ Σ(required_count)
        //
        // Phase 9-α 暫定実装:
        // 各 position の avg_rate は、現時点ではチーム全体の MEMBER_AVG を共用フォールバックする。
        // 真のポジション別集計は team_members.position_id 導入後（Phase 9-γ 予定）。
        // 結果として「全ポジションが同じ avg_rate を持つ」加重平均となるが、
        // Σ(rate × n) / Σ(n) = rate × Σn / Σn = rate であり、最終的な avg は
        // チーム全体平均と一致する。これは設計書 §4.1 の境界ケース表で
        // 「該当メンバー 0 人時に POSITION_NO_RATE_DATA」を扱うべきと定められたフォールバック動作の一形態。
        BigDecimal teamAvg = rateQueryRepository.averageRateForPositionFallback(
                request.teamId(), LocalDate.now());

        List<PositionBreakdown> breakdown = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        long totalRequired = 0L;
        boolean anyHasMembers = false;

        for (PositionRequiredCount p : positions) {
            // Phase 9-α: ポジション別メンバー数は取得不能のため、
            // teamAvg が non-null であれば「ポジション全体共有」として扱う。
            // teamAvg=null（チームに時給設定者 0）の場合のみ POSITION_NO_RATE_DATA。
            BigDecimal posAvg = teamAvg;
            int memberCount = (teamAvg != null) ? 1 : 0; // 暫定値（Phase 9-γ で正確化）
            if (posAvg == null) {
                warnings.add("POSITION_NO_RATE_DATA");
                breakdown.add(new PositionBreakdown(p.positionId(), null, 0, p.requiredCount()));
                continue;
            }
            anyHasMembers = true;
            weightedSum = weightedSum.add(posAvg.multiply(BigDecimal.valueOf(p.requiredCount())));
            totalRequired += p.requiredCount();
            breakdown.add(new PositionBreakdown(
                    p.positionId(),
                    posAvg.setScale(2, RoundingMode.HALF_UP),
                    memberCount,
                    p.requiredCount()));
        }

        BigDecimal avgRate;
        if (!anyHasMembers || totalRequired == 0) {
            avgRate = BigDecimal.ZERO;
            if (!warnings.contains("AVG_RATE_ZERO")) {
                warnings.add("AVG_RATE_ZERO");
            }
        } else {
            avgRate = weightedSum.divide(BigDecimal.valueOf(totalRequired),
                    new MathContext(12, RoundingMode.HALF_UP))
                    .setScale(2, RoundingMode.HALF_UP);
            if (avgRate.compareTo(BigDecimal.ZERO) == 0) {
                warnings.add("AVG_RATE_ZERO");
            }
        }

        return new AggregationResult(avgRate, warnings, breakdown);
    }
}
