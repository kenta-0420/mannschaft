package com.mannschaft.app.actionmemo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * F02.5 気分集計レスポンス DTO。
 *
 * <p>設計書 §9 Phase 4「気分集計表示」に対応。
 * 指定期間内の mood 分布を返す。</p>
 *
 * <pre>
 * { "total": 30, "distribution": { "GREAT": 5, "GOOD": 10, "OK": 8, "TIRED": 4, "BAD": 3 } }
 * </pre>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoodStatsResponse {

    private int total;

    private Map<String, Integer> distribution;
}
