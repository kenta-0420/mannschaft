package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.TeamFriendFolderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * フレンドフォルダリポジトリ。
 *
 * <p>
 * {@code team_friend_folders} テーブルへのアクセス経路。Entity のフィールド名は
 * {@code ownerTeamId} だが、DB カラム名は {@code team_id} であることに留意する。
 * </p>
 */
public interface TeamFriendFolderRepository extends JpaRepository<TeamFriendFolderEntity, Long> {

    /**
     * 指定チームが所有する、論理削除されていないフォルダ一覧を並び順で取得する。
     *
     * @param ownerTeamId フォルダ所有チーム ID
     * @return フォルダ一覧（folder_order 昇順）
     */
    List<TeamFriendFolderEntity> findByOwnerTeamIdAndDeletedAtIsNullOrderByFolderOrder(Long ownerTeamId);

    /**
     * 指定チームが所有する、論理削除されていない特定フォルダを取得する。
     * 所有権チェックを含むため、アクセス制御に使用できる。
     *
     * @param id          フォルダ ID
     * @param ownerTeamId フォルダ所有チーム ID
     * @return フォルダエンティティ（存在しないか所有権がなければ空）
     */
    Optional<TeamFriendFolderEntity> findByIdAndOwnerTeamIdAndDeletedAtIsNull(Long id, Long ownerTeamId);

    /**
     * 指定チームが所有する、論理削除されていないフォルダ数を取得する。
     * フォルダ上限チェック（1チームあたり最大 20 フォルダ）に使用する。
     *
     * @param ownerTeamId フォルダ所有チーム ID
     * @return アクティブなフォルダ数
     */
    long countByOwnerTeamIdAndDeletedAtIsNull(Long ownerTeamId);
}
