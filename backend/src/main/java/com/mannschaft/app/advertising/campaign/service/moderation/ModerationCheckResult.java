package com.mannschaft.app.advertising.campaign.service.moderation;

import java.util.List;

/**
 * F09.17 Phase 11-b {@code AdContentModerator#check(String)} の戻り値。
 *
 * @param detectedWords  検出された NG ワード一覧 (空 List = NG なし)
 * @param suggestedAction 検出結果に基づく推奨アクション
 */
public record ModerationCheckResult(
        List<DetectedNgWord> detectedWords,
        SuggestedModerationAction suggestedAction
) {
    public ModerationCheckResult {
        detectedWords = detectedWords == null ? List.of() : List.copyOf(detectedWords);
    }
}
