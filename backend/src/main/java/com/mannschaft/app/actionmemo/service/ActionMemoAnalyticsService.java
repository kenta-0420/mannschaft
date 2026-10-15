package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.MoodStatsResponse;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F02.5 行動メモ 集計・分析サービス。
 *
 * <p>気分（mood）分布など集計系の処理を担当する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoAnalyticsService {

    private final ActionMemoRepository memoRepository;

    /**
     * 期間内の気分（mood）分布を取得する。
     *
     * <p>設計書 §9 Phase 4「気分集計表示」。
     * {@code mood_enabled = true} のユーザーのみ意味があるが、
     * API 自体は全ユーザーに開放（0件なら {@code total: 0} で返す）。</p>
     *
     * @param userId 現在のユーザー
     * @param from   期間開始日（含む）
     * @param to     期間終了日（含む）
     * @return 気分分布レスポンス
     */
    public MoodStatsResponse getMoodStats(Long userId, LocalDate from, LocalDate to) {
        List<ActionMemoEntity> memos = memoRepository
                .findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(userId, from, to);

        Map<ActionMemoMood, Integer> counts = new EnumMap<>(ActionMemoMood.class);
        for (ActionMemoEntity memo : memos) {
            if (memo.getMood() != null) {
                counts.merge(memo.getMood(), 1, Integer::sum);
            }
        }

        int total = counts.values().stream().mapToInt(Integer::intValue).sum();

        // EnumMap → String キーの LinkedHashMap に変換（JSON の順序保持）
        Map<String, Integer> distribution = new LinkedHashMap<>();
        for (ActionMemoMood mood : ActionMemoMood.values()) {
            distribution.put(mood.name(), counts.getOrDefault(mood, 0));
        }

        return MoodStatsResponse.builder()
                .total(total)
                .distribution(distribution)
                .build();
    }
}
