package com.mannschaft.app.payment.spi;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 課金ゲート対象コンテンツが指定スコープに実在するかを判定するStrategy。
 *
 * <p>各コンテンツ機能が自身のRepositoryを使って実装し、payment機能から
 * 他機能のテーブルを直接参照しないための境界である。</p>
 */
public interface ContentGateResolver {

    /** 課金判定用の実在実体とscopeを返す。未対応typeや不存在は空を返す。 */
    default Optional<ContentGateTarget> resolveForAccess(Long contentId) {
        return Optional.empty();
    }

    /**
     * {@link #resolveForAccess} の一括版（ID 数に比例してクエリを発行しないこと）。
     *
     * <p>既定は空（＝どの ID も実在確認できない）で、呼び出し側の課金判定は fail-closed に倒れる
     * （target 欠落は HIDDEN）。一括で使う種別だけが上書きする。</p>
     *
     * @param contentIds コンテンツ ID 集合
     * @return 実在するコンテンツの ID → 実体と scope（不存在・削除済みは含めない）
     */
    default Map<Long, ContentGateTarget> resolveForAccessBatch(Collection<Long> contentIds) {
        return Map.of();
    }

    /**
     * 担当するコンテンツ種別を返す。
     *
     * @return {@code ContentGateType} の値
     */
    String contentType();

    /**
     * コンテンツが指定されたチームまたは組織に属するかを判定する。
     *
     * @param contentId     コンテンツID
     * @param teamId        チームID（組織スコープではnull）
     * @param organizationId 組織ID（チームスコープではnull）
     * @return 指定スコープ内に実在する場合true
     */
    boolean existsInScope(Long contentId, Long teamId, Long organizationId);
}
