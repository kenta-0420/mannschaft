package com.mannschaft.app.shift.assignment;

import com.mannschaft.app.shift.AssignmentStrategyType;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.dto.AssignmentParametersDto;
import com.mannschaft.app.shift.dto.AssignmentWarningDto;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 貪欲法（Greedy Algorithm）によるシフト自動割当 Strategy 実装。
 * <p>
 * アルゴリズム概要:
 * 1. スロットをカバー人数不足（必要人数が多い順）でソート
 * 2. 各スロットに対し、希望者をスコア降順で評価して上位から割当
 * 3. ABSOLUTE_REST のユーザーは強制スキップ
 * 4. 勤務制約違反は WARNING として記録
 * </p>
 */
@Slf4j
@Component
public class GreedyShiftAssignmentStrategy implements ShiftAssignmentStrategy {

    @Override
    public AssignmentStrategyType getStrategyType() {
        return AssignmentStrategyType.GREEDY_V1;
    }

    @Override
    public AssignmentResult assign(AssignmentContext context) {
        List<ProposedAssignment> proposals = new ArrayList<>();
        List<AssignmentWarningDto> warnings = new ArrayList<>();

        AssignmentParametersDto params = context.parameters();

        // スロットをカバー必要人数の多い順でソート（人手不足スロットを優先）
        List<ShiftSlotEntity> sortedSlots = context.slots().stream()
                .sorted(Comparator.comparingInt(ShiftSlotEntity::getRequiredCount).reversed())
                .toList();

        // スケジュール内の全希望を userId → (日付 → ShiftPreference) にマップ化
        Map<Long, Map<LocalDate, ShiftPreference>> preferenceMap = buildPreferenceMap(context.requests());

        // 勤務制約を userId → MemberWorkConstraintEntity にマップ化（null=チームデフォルト）
        Map<Long, MemberWorkConstraintEntity> constraintMap = buildConstraintMap(context.constraints());
        MemberWorkConstraintEntity teamDefault = constraintMap.get(null);

        // 割当済み日数を追跡（ユーザーID → 割当済み日付リスト）
        Map<Long, List<LocalDate>> assignedDatesMap = new HashMap<>();

        for (ShiftSlotEntity slot : sortedSlots) {
            // このスロットで対象となる全ユーザー（希望を出しているユーザー）を取得
            List<Long> candidateUserIds = getCandidateUserIds(slot, context.requests());

            // 各候補者のスコアを計算して降順ソート
            List<ScoredCandidate> scoredCandidates = candidateUserIds.stream()
                    .map(userId -> {
                        ShiftPreference pref = preferenceMap
                                .getOrDefault(userId, Map.of())
                                .getOrDefault(slot.getSlotDate(), null);

                        // ABSOLUTE_REST は割当不可
                        if (pref == null || !pref.isAssignable()) {
                            return null;
                        }

                        MemberWorkConstraintEntity constraint =
                                constraintMap.getOrDefault(userId, teamDefault);

                        double score = calculateScore(
                                pref,
                                constraint,
                                assignedDatesMap.getOrDefault(userId, List.of()),
                                params);

                        return new ScoredCandidate(userId, score);
                    })
                    .filter(sc -> sc != null)
                    .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed())
                    .toList();

            // 必要人数分だけ割当
            int filled = 0;
            for (ScoredCandidate candidate : scoredCandidates) {
                if (filled >= slot.getRequiredCount()) {
                    break;
                }

                Long userId = candidate.userId();
                List<LocalDate> assignedDates = assignedDatesMap
                        .computeIfAbsent(userId, k -> new ArrayList<>());

                // 勤務制約チェック
                MemberWorkConstraintEntity constraint =
                        constraintMap.getOrDefault(userId, teamDefault);

                if (params.respectWorkConstraints() != null && params.respectWorkConstraints()
                        && constraint != null) {
                    // 月最大勤務日数チェック
                    if (constraint.getMaxMonthlyDays() != null
                            && assignedDates.size() >= constraint.getMaxMonthlyDays()) {
                        warnings.add(new AssignmentWarningDto(
                                "MONTHLY_DAYS_EXCEEDED",
                                String.format("ユーザー %d は月最大勤務日数 (%d 日) に達しています",
                                        userId, constraint.getMaxMonthlyDays()),
                                slot.getId(),
                                userId));
                        continue;
                    }

                    // 連続勤務日数チェック
                    if (constraint.getMaxConsecutiveDays() != null
                            && isConsecutiveDaysExceeded(assignedDates, slot.getSlotDate(),
                            constraint.getMaxConsecutiveDays())) {
                        warnings.add(new AssignmentWarningDto(
                                "CONSECUTIVE_DAYS_EXCEEDED",
                                String.format("ユーザー %d は最大連続勤務日数 (%d 日) を超えます",
                                        userId, constraint.getMaxConsecutiveDays()),
                                slot.getId(),
                                userId));
                        continue;
                    }
                }

                // 割当追加
                proposals.add(new ProposedAssignment(
                        slot.getId(),
                        userId,
                        BigDecimal.valueOf(candidate.score()).setScale(4, RoundingMode.HALF_UP)));
                assignedDates.add(slot.getSlotDate());
                filled++;

                log.debug("割当: slotId={}, userId={}, score={}", slot.getId(), userId, candidate.score());
            }

            // 必要人数を充足できなかった場合は警告
            if (filled < slot.getRequiredCount()) {
                warnings.add(new AssignmentWarningDto(
                        "UNASSIGNED_SLOT",
                        String.format("スロット %d は必要人数 %d 人に対して %d 人しか割当できませんでした",
                                slot.getId(), slot.getRequiredCount(), filled),
                        slot.getId(),
                        null));
                log.warn("スロット未充足: slotId={}, required={}, filled={}",
                        slot.getId(), slot.getRequiredCount(), filled);
            }
        }

        return new AssignmentResult(proposals, warnings);
    }

    /**
     * 割当スコアを計算する。
     * score = preferenceScore * preferenceWeight
     *       + fairnessScore * fairnessWeight
     *       - consecutivePenalty * consecutivePenaltyWeight
     */
    private double calculateScore(
            ShiftPreference preference,
            MemberWorkConstraintEntity constraint,
            List<LocalDate> assignedDates,
            AssignmentParametersDto params) {

        // 希望スコア（PREFERRED=100, AVAILABLE=0, WEAK_REST=-30, STRONG_REST=-80）
        double preferenceScore = preference.toAssignmentScore();

        // 公平性スコア: 未使用勤務日数の割合 * 100
        double fairnessScore = 100.0;
        if (constraint != null && constraint.getMaxMonthlyDays() != null
                && constraint.getMaxMonthlyDays() > 0) {
            int remaining = constraint.getMaxMonthlyDays() - assignedDates.size();
            fairnessScore = (double) remaining / constraint.getMaxMonthlyDays() * 100.0;
        }

        // 連続勤務ペナルティ: 連続勤務日数が多いほど高い
        double consecutivePenalty = calculateConsecutivePenalty(
                assignedDates, constraint != null ? constraint.getMaxConsecutiveDays() : null);

        double pw = params.preferenceWeight() != null
                ? params.preferenceWeight().doubleValue() : 0.6;
        double fw = params.fairnessWeight() != null
                ? params.fairnessWeight().doubleValue() : 0.3;
        double cpw = params.consecutivePenaltyWeight() != null
                ? params.consecutivePenaltyWeight().doubleValue() : 0.1;

        return preferenceScore * pw + fairnessScore * fw - consecutivePenalty * cpw;
    }

    /**
     * 連続勤務ペナルティを計算する。
     * 現在の連続勤務日数 / 最大連続勤務日数 * 100 を返す。
     */
    private double calculateConsecutivePenalty(List<LocalDate> assignedDates, Integer maxConsecutiveDays) {
        if (assignedDates.isEmpty()) {
            return 0.0;
        }

        // 最近の連続勤務日数を計算
        List<LocalDate> sorted = assignedDates.stream().sorted().toList();
        int consecutive = 1;
        int maxConsecutive = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).plusDays(1).equals(sorted.get(i))) {
                consecutive++;
                maxConsecutive = Math.max(maxConsecutive, consecutive);
            } else {
                consecutive = 1;
            }
        }

        if (maxConsecutiveDays != null && maxConsecutiveDays > 0) {
            return (double) maxConsecutive / maxConsecutiveDays * 100.0;
        }
        // 制約がない場合は連続日数を直接ペナルティに
        return maxConsecutive * 10.0;
    }

    /**
     * 指定日を追加すると連続勤務日数が制限を超えるかチェックする。
     */
    private boolean isConsecutiveDaysExceeded(
            List<LocalDate> assignedDates, LocalDate targetDate, int maxConsecutiveDays) {
        if (assignedDates.isEmpty()) {
            return false;
        }

        // targetDate を含めた日付リストで連続日数を再計算
        List<LocalDate> dates = new ArrayList<>(assignedDates);
        dates.add(targetDate);
        List<LocalDate> sorted = dates.stream().sorted().toList();

        int consecutive = 1;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i - 1).plusDays(1).equals(sorted.get(i))) {
                consecutive++;
                if (consecutive > maxConsecutiveDays) {
                    return true;
                }
            } else {
                consecutive = 1;
            }
        }
        return false;
    }

    /**
     * 対象スロットに希望を出しているユーザーIDリストを返す。
     * スロット希望（slotId 一致）と日付希望（日付一致・slotId null）を集約する。
     */
    private List<Long> getCandidateUserIds(ShiftSlotEntity slot, List<ShiftRequestEntity> requests) {
        return requests.stream()
                .filter(req -> {
                    // スロット指定の希望またはその日付の希望
                    boolean slotMatch = slot.getId().equals(req.getSlotId());
                    boolean dateMatch = req.getSlotId() == null
                            && slot.getSlotDate().equals(req.getSlotDate());
                    return slotMatch || dateMatch;
                })
                .map(ShiftRequestEntity::getUserId)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 希望リストを userId → (日付 → ShiftPreference) のマップに変換する。
     */
    private Map<Long, Map<LocalDate, ShiftPreference>> buildPreferenceMap(
            List<ShiftRequestEntity> requests) {
        Map<Long, Map<LocalDate, ShiftPreference>> map = new HashMap<>();
        for (ShiftRequestEntity req : requests) {
            map.computeIfAbsent(req.getUserId(), k -> new HashMap<>())
                    .put(req.getSlotDate(), req.getPreference());
        }
        return map;
    }

    /**
     * 勤務制約を userId → entity のマップに変換する（userId=null はチームデフォルト）。
     */
    private Map<Long, MemberWorkConstraintEntity> buildConstraintMap(
            List<MemberWorkConstraintEntity> constraints) {
        Map<Long, MemberWorkConstraintEntity> map = new HashMap<>();
        for (MemberWorkConstraintEntity c : constraints) {
            map.put(c.getUserId(), c);
        }
        return map;
    }

    /**
     * スコアと userId を保持する内部レコード。
     */
    private record ScoredCandidate(Long userId, double score) {}
}
