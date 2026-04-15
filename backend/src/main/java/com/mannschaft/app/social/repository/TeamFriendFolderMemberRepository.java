package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.TeamFriendFolderMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * フレンドフォルダメンバーリポジトリ。
 *
 * <p>
 * {@code team_friend_folder_members} 中間テーブルへのアクセス経路。
 * </p>
 */
public interface TeamFriendFolderMemberRepository
        extends JpaRepository<TeamFriendFolderMemberEntity, Long> {

    /**
     * 指定フォルダに所属するメンバー一覧を取得する。
     *
     * @param folderId フォルダ ID
     * @return メンバー一覧
     */
    List<TeamFriendFolderMemberEntity> findByFolderId(Long folderId);

    /**
     * 指定フォルダから特定のフレンドチーム関係を取り外す。
     *
     * @param folderId     フォルダ ID
     * @param teamFriendId フレンドチーム関係 ID
     */
    void deleteByFolderIdAndTeamFriendId(Long folderId, Long teamFriendId);

    /**
     * 指定フォルダに特定のフレンドチーム関係が登録済みかを判定する。
     *
     * @param folderId     フォルダ ID
     * @param teamFriendId フレンドチーム関係 ID
     * @return 登録済みの場合 {@code true}
     */
    boolean existsByFolderIdAndTeamFriendId(Long folderId, Long teamFriendId);
}
