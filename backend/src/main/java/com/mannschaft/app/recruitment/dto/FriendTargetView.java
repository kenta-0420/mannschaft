package com.mannschaft.app.recruitment.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * F22.1 市: フレンド宛非公開札の宛先ビュー（02_api_design §4 レスポンス）。
 *
 * <p>{@code recruitment_friend_targets} の 1 行に対応する。札主（ADMIN）向けの
 * レスポンスでのみ返し、公開市 API（PII 抑制）には含めない。</p>
 */
@Getter
@AllArgsConstructor
public class FriendTargetView {

    /** 宛先粒度（ALL_FRIENDS / FOLDER / TEAM）。 */
    private final String targetKind;

    /** FOLDER のときのフレンドフォルダID。 */
    private final Long folderId;

    /** TEAM のときの宛先チームID。 */
    private final Long teamId;
}
