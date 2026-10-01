package com.mannschaft.app.member.service;

import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;

import java.time.Instant;
import java.util.List;

/**
 * サブタブ可視性設定の、応答組み立て用スナップショット（スカラーのみ。Entity を外へ出さない）。
 *
 * <p>{@link MemberSubtabVisibilityWriter#applyUpdates} が書き込み TX のコミット前に再読込して作る。
 * 段取り役 {@link MemberSubtabVisibilityService} はこれと先読みした表示名だけで応答を組み立て、
 * コミット後に応答用の DB アクセスをしない（PR #3387 D-3T 根治。軍議書 §2.1(c)）。</p>
 *
 * @param rows DB に設定行があるサブタブ（既定値のサブタブは含まない）
 */
public record MemberSubtabVisibilitySnapshot(List<Row> rows) {

    /**
     * 1サブタブ分の設定行。
     */
    public record Row(String subtabKey, MinRole minRole, Long updatedBy, Instant updatedAt) {
    }

    static MemberSubtabVisibilitySnapshot of(List<MemberSubtabRoleVisibilityEntity> entities) {
        return new MemberSubtabVisibilitySnapshot(entities.stream()
                .map(e -> new Row(e.getSubtabKey(), e.getMinRole(), e.getUpdatedBy(), e.getUpdatedAt()))
                .toList());
    }
}
