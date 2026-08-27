package com.mannschaft.app.payment.spi;

/**
 * 課金ゲート対象コンテンツが指定スコープに実在するかを判定するStrategy。
 *
 * <p>各コンテンツ機能が自身のRepositoryを使って実装し、payment機能から
 * 他機能のテーブルを直接参照しないための境界である。</p>
 */
public interface ContentGateResolver {

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
