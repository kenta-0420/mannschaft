package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentFriendTargetKind;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F22.1 市: フレンド宛非公開札（{@code visibility='FRIEND_TEAMS_ONLY'}）の宛先指定リクエスト
 * （02_api_design §4）。
 *
 * <p>3 粒度（全体 / フォルダ / 個別チーム）を表す。粒度ごとに必須項目が異なるため、
 * {@link #isConsistent()} で組合せ整合を表明する（Service 層が検証時に利用）。</p>
 *
 * <ul>
 *   <li>{@code ALL_FRIENDS} → folderId / teamId ともに null</li>
 *   <li>{@code FOLDER}      → folderId 必須・teamId null</li>
 *   <li>{@code TEAM}        → teamId 必須・folderId null</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class FriendTargetRequest {

    /** 宛先粒度。 */
    @NotNull
    private final RecruitmentFriendTargetKind targetKind;

    /** {@code FOLDER} のとき必須（F01.5 フレンドフォルダID）。 */
    private final Long folderId;

    /** {@code TEAM} のとき必須（宛先チームID）。 */
    private final Long teamId;

    /**
     * {@code targetKind} と参照列の整合を判定する。
     *
     * @return 整合していれば true
     */
    public boolean isConsistent() {
        if (targetKind == null) {
            return false;
        }
        return switch (targetKind) {
            case ALL_FRIENDS -> folderId == null && teamId == null;
            case FOLDER -> folderId != null && teamId == null;
            case TEAM -> teamId != null && folderId == null;
        };
    }
}
