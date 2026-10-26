package com.mannschaft.app.recruitment;

/**
 * F22.1 市: フレンド宛非公開札（{@code FRIEND_TEAMS_ONLY}）の宛先粒度。
 *
 * <p>{@code recruitment_friend_targets.target_kind} に対応する。
 * 利用者は 3 粒度を混在指定でき、配信・アクセス解決時に集合和（UNION）で解決する
 * （01_data_model §4 / 02_api_design §7）。</p>
 */
public enum RecruitmentFriendTargetKind {
    /** 札主チームの全成立フレンドチーム宛（folder_id / team_id ともに NULL）。 */
    ALL_FRIENDS,
    /** F01.5 フレンドフォルダ単位の宛先（folder_id 必須・team_id NULL）。 */
    FOLDER,
    /** 個別チーム単位の宛先（team_id 必須・folder_id NULL）。 */
    TEAM
}
