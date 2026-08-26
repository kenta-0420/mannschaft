package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * F22.1 市: フレンド宛非公開札の宛先リポジトリ。
 *
 * <p>主キーは UUIDv7（{@link RecruitmentFriendTargetEntity} は {@code UuidV7Entity} 継承）。
 * 宛先解決・配信・フォルダ削除連動で使用する（02_api_design §7）。</p>
 */
public interface RecruitmentFriendTargetRepository
        extends JpaRepository<RecruitmentFriendTargetEntity, UUID> {

    /**
     * 札に紐づく全宛先を取得する（札→宛先一覧 / 配信・アクセス解決）。
     *
     * @param listingId 札ID
     * @return 宛先リスト
     */
    List<RecruitmentFriendTargetEntity> findByListingId(Long listingId);

    /**
     * 指定チーム宛（{@code target_kind='TEAM'}）の宛先を取得する。
     * 「自チームに届いた札」一覧で使用する。
     *
     * @param teamIds 宛先チームID集合
     * @return 宛先リスト
     */
    List<RecruitmentFriendTargetEntity> findByTeamIdIn(Collection<Long> teamIds);

    /**
     * 指定フォルダ宛（{@code target_kind='FOLDER'}）の宛先を取得する。
     * フォルダ削除時の孤立対策で使用する。
     *
     * @param folderId フレンドフォルダID
     * @return 宛先リスト
     */
    List<RecruitmentFriendTargetEntity> findByFolderId(Long folderId);

    /**
     * 指定札に紐づく宛先件数を返す。
     * {@code FRIEND_TEAMS_ONLY} の OPEN 遷移時の MARKET_002（0 件禁止）チェックで使用する。
     *
     * @param listingId 札ID
     * @return 宛先件数
     */
    int countByListingId(Long listingId);

    /**
     * フレンドフォルダ削除時に、当該フォルダ宛の宛先行を削除する（孤立対策・イベント連携）。
     *
     * @param folderId フレンドフォルダID
     * @return 削除件数
     */
    @Modifying
    int deleteByFolderId(Long folderId);

    /**
     * 札の宛先を全削除する（宛先再設定時に使用）。
     *
     * @param listingId 札ID
     * @return 削除件数
     */
    @Modifying
    int deleteByListingId(Long listingId);
}
