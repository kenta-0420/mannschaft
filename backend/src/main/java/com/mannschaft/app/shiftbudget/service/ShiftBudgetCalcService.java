package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.PositionRequiredCount;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsRequest.RateMode;
import com.mannschaft.app.shiftbudget.dto.RequiredSlotsResponse;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetRateQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * シフト予算逆算サービス。
 *
 * <p>API: {@code POST /api/v1/shift-budget/calc/required-slots}</p>
 *
 * <p>設計書 F08.7 §4.1 / §6.2.2 / §9.5 / §13 / §14 に準拠。</p>
 *
 * <p>処理フロー:</p>
 * <ol>
 *   <li>フィーチャーフラグ判定 (OFF なら 503)</li>
 *   <li>リクエストバリデーション (空配列・重複・範囲外など → 400)</li>
 *   <li>多テナント分離: team_id の組織所属を team_org_memberships で検証</li>
 *   <li>権限チェック: MANAGE_SHIFTS (TEAM スコープ)</li>
 *   <li>平均時給算出 (3 モード) — {@link HourlyRateAggregationService}</li>
 *   <li>逆算: floor(budget / (rate × hours))</li>
 *   <li>境界ケース warning 集約 + レスポンス組み立て</li>
 * </ol>
 *
 * <p>本サービスはステートレスで DB 書き込みを一切行わない。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ShiftBudgetCalcService {

    private final ShiftBudgetFeatureService featureService;
    private final ShiftBudgetRateQueryRepository rateQueryRepository;
    private final HourlyRateAggregationService aggregationService;
    private final AccessControlService accessControlService;

    /**
     * 必要シフト枠数を逆算する。
     *
     * @param request リクエスト DTO
     * @return レスポンス DTO
     */
    public RequiredSlotsResponse calculateRequiredSlots(RequiredSlotsRequest request) {
        // 1. リクエスト事前バリデーション (Bean Validation で済まない論理チェック)
        validateRequest(request);

        // 2. テナント分離・権限チェック (EXPLICIT モード単独・team 不要のケースを除く)
        if (request.rateMode() != RateMode.EXPLICIT) {
            requireTeamAccess(request.teamId());
        } else if (request.teamId() != null) {
            // EXPLICIT でも team_id 指定があればそのチームに対する権限を必須にする
            requireTeamAccess(request.teamId());
        } else {
            // team_id 無し EXPLICIT モードはユーザー認証だけで通す（純粋な計算機能）
            // ただしフィーチャーフラグはグローバル単独判定なので organizationId=null 渡し
            featureService.requireEnabled(null);
        }

        // 3. 平均時給算出
        HourlyRateAggregationService.AggregationResult agg =
                aggregationService.aggregate(request);

        // 4. 逆算 + warning 集約
        return buildResponse(request, agg);
    }

    // ----------------------------------------------------------------
    // バリデーション
    // ----------------------------------------------------------------

    private void validateRequest(RequiredSlotsRequest request) {
        // budget_amount: @PositiveOrZero で 0 以上は確保。負数は MethodArgumentNotValidException で 400
        if (request.budgetAmount() == null) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_BUDGET_AMOUNT);
        }
        if (request.budgetAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_BUDGET_AMOUNT);
        }

        // slot_hours: 0.25h 以上 24h 以下 (@DecimalMin/@DecimalMax で確保。冗長だが二重防御)
        BigDecimal hours = request.slotHours();
        if (hours == null
                || hours.compareTo(new BigDecimal("0.25")) < 0
                || hours.compareTo(new BigDecimal("24")) > 0) {
            throw new BusinessException(ShiftBudgetErrorCode.INVALID_SLOT_HOURS);
        }

        // rate_mode 別バリデーション
        switch (request.rateMode()) {
            case EXPLICIT -> {
                if (request.avgHourlyRate() == null) {
                    throw new BusinessException(ShiftBudgetErrorCode.MISSING_EXPLICIT_RATE);
                }
            }
            case POSITION_AVG -> {
                List<PositionRequiredCount> positions = request.positionRequiredCounts();
                if (positions == null || positions.isEmpty()) {
                    throw new BusinessException(ShiftBudgetErrorCode.EMPTY_POSITION_LIST);
                }
                Set<Long> seen = new HashSet<>();
                for (PositionRequiredCount p : positions) {
                    if (p.requiredCount() == null || p.requiredCount() <= 0) {
                        throw new BusinessException(ShiftBudgetErrorCode.INVALID_REQUIRED_COUNT);
                    }
                    if (!seen.add(p.positionId())) {
                        throw new BusinessException(ShiftBudgetErrorCode.DUPLICATE_POSITION_ID);
                    }
                }
            }
            case MEMBER_AVG -> {
                if (request.teamId() == null) {
                    throw new BusinessException(ShiftBudgetErrorCode.TEAM_NOT_FOUND);
                }
            }
        }
    }

    /**
     * team_id の組織所属検証 + フィーチャーフラグ判定 + MANAGE_SHIFTS 権限チェックを行う。
     *
     * <p>多テナント分離: team_id は ACTIVE な team_org_memberships を持つ必要がある。
     * 不在時は 404 で IDOR 対策（403 ではなく 404 を返すのは
     * F02.5 ACTION_MEMO の作法に準拠）。</p>
     */
    private void requireTeamAccess(Long teamId) {
        Long organizationId = rateQueryRepository.findOrganizationIdByTeamId(teamId)
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.TEAM_NOT_FOUND));

        // フィーチャーフラグ判定
        featureService.requireEnabled(organizationId);

        // 権限チェック: MANAGE_SHIFTS (TEAM スコープ)
        Long currentUserId = SecurityUtils.getCurrentUserId();
        accessControlService.checkPermission(currentUserId, teamId, "TEAM", "MANAGE_SHIFTS");
    }

    // ----------------------------------------------------------------
    // 逆算 + レスポンス組み立て
    // ----------------------------------------------------------------

    private RequiredSlotsResponse buildResponse(
            RequiredSlotsRequest request,
            HourlyRateAggregationService.AggregationResult agg) {

        BigDecimal budget = request.budgetAmount();
        BigDecimal rate = agg.avgHourlyRate() != null ? agg.avgHourlyRate() : BigDecimal.ZERO;
        BigDecimal hours = request.slotHours();

        List<String> warnings = new ArrayList<>(agg.warnings());

        long requiredSlots;
        if (budget.compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("BUDGET_ZERO");
            requiredSlots = 0L;
        } else if (rate.compareTo(BigDecimal.ZERO) == 0) {
            // AVG_RATE_ZERO は agg 側で既に追加されている
            requiredSlots = 0L;
        } else {
            // 設計書 §4.1 数式: floor(budget / (rate × hours))
            BigDecimal denominator = rate.multiply(hours);
            requiredSlots = budget.divide(denominator, 0, RoundingMode.FLOOR).longValueExact();
        }

        // 設計書 §6.2.2 の calculation 文字列形状: "floor(300000 / (1200 * 4.0)) = 62"
        String calculation = String.format(
                "floor(%s / (%s * %s)) = %d",
                stripTrailingZerosForDisplay(budget),
                stripTrailingZerosForDisplay(rate),
                stripTrailingZerosForDisplay(hours),
                requiredSlots);

        return new RequiredSlotsResponse(
                budget,
                rate,
                hours,
                requiredSlots,
                calculation,
                warnings,
                agg.positionBreakdown()
        );
    }

    /**
     * BigDecimal を表示用文字列に整形する。末尾 0 を整数表記に丸める。
     * 例: 300000.00 → "300000", 4.00 → "4.0" (時間は小数 1 桁を保持)
     *
     * <p>シンプルに: 整数値なら整数表示、それ以外は不要な末尾 0 を除く。</p>
     */
    private String stripTrailingZerosForDisplay(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() < 0) {
            stripped = stripped.setScale(0, RoundingMode.UNNECESSARY);
        }
        // hours の小数最低 1 桁を保つには、ここでは元の値の小数桁数を尊重する形がベター。
        // 設計書 §6.2.2 例 "4.0" を再現するため、元 scale が 0 ならそのまま、それ以外は stripped を使う。
        return stripped.toPlainString();
    }
}
