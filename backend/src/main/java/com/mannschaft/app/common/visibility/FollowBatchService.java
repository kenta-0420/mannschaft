package com.mannschaft.app.common.visibility;

/**
 * F00 共通可視性基盤の {@link StandardVisibility#FOLLOWERS_ONLY} 評価用 SPI。
 *
 * <p>{@link AbstractContentVisibilityResolver} は本 IF を任意依存として受け取り、
 * 機能側 visibility が {@code FOLLOWERS_ONLY} に正規化されたコンテンツに対して
 * 「閲覧者が作成者をフォローしているか」をバルクで判定する。</p>
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6
 * （AbstractContentVisibilityResolver のサブクラス契約）/ §19.1（ファイル一覧）。</p>
 *
 * <p><strong>実装は別タスク（A-3 系統）の責務。</strong> 本 Phase A-1c では IF のみを
 * 提供し、Spring Bean としての実装が未配線でも {@link AbstractContentVisibilityResolver}
 * が起動できるよう、依存は {@code @Autowired(required = false)} 相当の任意注入とする。</p>
 *
 * <p>FOLLOWERS_ONLY を扱わない Resolver では Bean が無くても問題ない（呼び出されない）。</p>
 */
public interface FollowBatchService {

    /**
     * 単一の {@code viewerUserId} が {@code authorUserId} をフォローしているか判定する。
     *
     * <p>FOLLOWERS_ONLY visibility 判定の基本 API。{@code viewerUserId} または
     * {@code authorUserId} が {@code null} の場合は fail-closed で {@code false} を返すこと。</p>
     *
     * @param viewerUserId 閲覧者 user_id
     * @param authorUserId 作成者 user_id
     * @return viewer が author をフォロー中なら {@code true}
     */
    boolean isFollower(Long viewerUserId, Long authorUserId);
}
