package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.FriendContentForwardEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * フレンドコンテンツ転送履歴リポジトリ。
 *
 * <p>
 * {@code friend_content_forwards} テーブルへのアクセス経路。
 * アクティブレコード（{@code is_revoked = FALSE}）のみを対象とするメソッドを中心に揃える。
 * </p>
 */
public interface FriendContentForwardRepository
        extends JpaRepository<FriendContentForwardEntity, Long> {

    /**
     * 特定投稿に対する自チームのアクティブな転送履歴を取得する。
     * 冪等性チェック（同一投稿の二重転送防止）に使用する。
     *
     * @param sourcePostId     転送元投稿 ID
     * @param forwardingTeamId 自チーム ID（転送実行側）
     * @return アクティブな転送履歴（存在しなければ空）
     */
    Optional<FriendContentForwardEntity> findBySourcePostIdAndForwardingTeamIdAndIsRevokedFalse(
            Long sourcePostId, Long forwardingTeamId);

    /**
     * 自チームが実行したアクティブな転送履歴一覧を、転送日時の降順で取得する。
     *
     * @param forwardingTeamId 自チーム ID
     * @param pageable         ページング
     * @return 転送履歴一覧
     */
    List<FriendContentForwardEntity> findByForwardingTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
            Long forwardingTeamId, Pageable pageable);

    /**
     * 自チーム投稿が他チームへ転送されたアクティブな履歴一覧を、転送日時の降順で取得する
     * （透明性確保用 API: 自分の投稿がどのフレンドチームに転送されたかの逆引き）。
     *
     * @param sourceTeamId 転送元チーム ID（自チーム = 投稿生成側）
     * @param pageable     ページング
     * @return 転送履歴一覧
     */
    List<FriendContentForwardEntity> findBySourceTeamIdAndIsRevokedFalseOrderByForwardedAtDesc(
            Long sourceTeamId, Pageable pageable);

    /**
     * 特定投稿に対するアクティブな転送履歴を全て取得する。
     * 転送元投稿の削除時に、関連する転送先投稿を連鎖処理するために使用する。
     *
     * @param sourcePostId 転送元投稿 ID
     * @return 転送履歴一覧
     */
    List<FriendContentForwardEntity> findBySourcePostIdAndIsRevokedFalse(Long sourcePostId);
}
