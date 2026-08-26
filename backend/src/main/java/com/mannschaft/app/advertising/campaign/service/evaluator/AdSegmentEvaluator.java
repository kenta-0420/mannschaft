package com.mannschaft.app.advertising.campaign.service.evaluator;

import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;

import java.util.Set;

/**
 * F09.17 セグメント単体評価インタフェース（戦略パターン）。
 *
 * <p>F09.2 SegmentEvaluator が将来本実装されたときは、本インタフェースの実装を
 * 「F09.2 委譲アダプタ」に差し替えるだけで切り替えられるよう、ドメイン内に閉じた
 * 抽象として導入する。F09.17 ドメイン内に置くことで F09.2 本体の侵入改修を避ける。</p>
 *
 * <h2>契約</h2>
 * <ul>
 *   <li>{@link #supports(AdSegmentType)} が {@code true} を返す型のみ {@link #resolveUserIds}
 *       で評価される。複数 evaluator がいる場合は最初に {@code supports} が true を返したものを採用する。</li>
 *   <li>{@link #resolveUserIds} は INCLUDE/EXCLUDE の論理は気にせず、
 *       「そのセグメント条件に該当する user_id 集合」のみを返す。論理演算は呼び出し側で行う。</li>
 *   <li>戻り値は {@code Set<Long>} とし、ストリーミング負荷の重い実装にする場合は
 *       実装側で chunked-load する。</li>
 * </ul>
 *
 * <h2>未サポート型の扱い</h2>
 * <p>{@link #supports} が false を返す評価器は当該セグメント評価をスキップされる。
 * Resolver 側は未サポート型のセグメントを受けた場合 {@link UnsupportedSegmentException} を投げる
 * （対処療法で空集合にしないこと — 根治治療原則）。</p>
 */
public interface AdSegmentEvaluator {

    /** この evaluator が受け持つセグメント型か判定する。 */
    boolean supports(AdSegmentType type);

    /**
     * セグメント条件に該当する user_id の集合を返す。
     *
     * @param segment 評価対象セグメント (INCLUDE/EXCLUDE どちらでも segment_value 解釈は同じ)
     * @return 該当 user_id 集合
     */
    Set<Long> resolveUserIds(AdAudienceSegment segment);

    /**
     * セグメント条件に該当する user_id の件数のみを返す。
     *
     * <p>{@link #resolveUserIds} と同じ論理条件・同じ segment_value バリデーションで評価するが、
     * user_id 集合をアプリ層に展開せず COUNT クエリで件数のみ取得する（メモリ展開回避）。
     * segment_value が不正な場合の例外挙動は {@link #resolveUserIds} と完全に一致させること。</p>
     *
     * @param segment 評価対象セグメント
     * @return 該当 user_id の件数
     */
    long countUserIds(AdAudienceSegment segment);
}
