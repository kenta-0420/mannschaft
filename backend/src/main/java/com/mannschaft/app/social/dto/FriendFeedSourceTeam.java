package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * フレンドフィード投稿の発信元チーム情報 DTO（F01.5 Phase 2）。
 */
@Getter
@Builder
public class FriendFeedSourceTeam {

    /** チーム ID */
    private final Long id;

    /** チーム名 */
    private final String name;
}
