package com.mannschaft.app.match.domain;

/**
 * 競技種別（F08.10 コア・多競技対応の識別子）。
 *
 * <p>{@code matches.sport}（VARCHAR・{@code @Enumerated(STRING)}・既定 'SOCCER'）に格納される。
 * 多競技カタログは案 A（enum＋定数）で確定（01 §D.3）。まず SOCCER を実装し、将来 enum を追加する。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/01_domain_and_ddl.md §D.1 / §D.3</p>
 */
public enum Sport {
    SOCCER
    // 将来: FUTSAL, BASKETBALL ...（sports/01_soccer.md §10 新競技の追加手順）
}
