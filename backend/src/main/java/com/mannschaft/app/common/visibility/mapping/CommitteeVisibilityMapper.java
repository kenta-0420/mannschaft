package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.common.visibility.StandardVisibility;

/**
 * F04.10 委員会 — {@link CommitteeVisibility} を {@link StandardVisibility} に正規化する Mapper。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §5.2 / §5.3 完全一致。
 *
 * <p>NAME_ONLY / NAME_AND_PURPOSE は「部分公開」というセマンティクスのため、
 * StandardVisibility の単純な可視/不可視では表現できず CUSTOM 行きとする。
 */
public final class CommitteeVisibilityMapper {

    private CommitteeVisibilityMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側 enum を {@link StandardVisibility} に変換する。
     *
     * @param v 機能側可視性 (non-null)
     * @return 正規化された {@link StandardVisibility} (non-null)
     */
    public static StandardVisibility toStandard(CommitteeVisibility v) {
        return switch (v) {
            case HIDDEN -> StandardVisibility.PRIVATE;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (部分公開 — 名前のみ表示)
            case NAME_ONLY -> StandardVisibility.CUSTOM;
            // §5.1.4 CUSTOM 運用規約参照、Resolver 内で個別実装
            // (部分公開 — 名前と目的を表示)
            case NAME_AND_PURPOSE -> StandardVisibility.CUSTOM;
        };
    }
}
