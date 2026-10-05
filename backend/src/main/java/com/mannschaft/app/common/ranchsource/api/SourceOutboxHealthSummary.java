package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

/** 件数と最古待機経過だけの有限四源集計。未取得を架空の0として返さない。 */
public record SourceOutboxHealthSummary(List<SourceOutboxHealthRow> sources, Instant observedAt) {
    public SourceOutboxHealthSummary {
        if (sources == null || sources.size() != 4 || observedAt == null) throw invalid();
        var seen = EnumSet.noneOf(RanchRewardSourceType.class);
        for (var row : sources) if (row == null || !seen.add(row.sourceType())) throw invalid();
        sources = List.copyOf(sources);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("源配送の集計定義が不正です");
    }
}
