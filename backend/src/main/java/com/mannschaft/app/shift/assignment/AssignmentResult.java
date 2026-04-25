package com.mannschaft.app.shift.assignment;

import com.mannschaft.app.shift.dto.AssignmentWarningDto;

import java.util.List;

/**
 * 自動割当アルゴリズムの実行結果。
 *
 * @param proposals 生成された割当提案リスト
 * @param warnings  警告リスト（未割当スロット・制約違反など）
 */
public record AssignmentResult(
        List<ProposedAssignment> proposals,
        List<AssignmentWarningDto> warnings
) {}
