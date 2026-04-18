package com.mannschaft.app.team.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * チームメンバー脱退イベント。
 *
 * <p>ユーザーがチームから脱退（または管理者によって除籍）されたときに発行される。</p>
 *
 * <p>購読者: {@link com.mannschaft.app.actionmemo.handler.ActionMemoTeamDefaultResetHandler}
 * — 脱退チームがデフォルト投稿先として設定されていた場合に NULL へリセットする。</p>
 */
@Getter
@RequiredArgsConstructor
public class TeamMemberRemovedEvent {

    /** 脱退したユーザーID */
    private final Long userId;

    /** 脱退したチームID */
    private final Long teamId;
}
