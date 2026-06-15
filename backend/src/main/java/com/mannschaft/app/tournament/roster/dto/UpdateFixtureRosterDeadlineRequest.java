package com.mannschaft.app.tournament.roster.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 試合のメンバー表提出締切を設定するリクエスト（F08.7.1/05 §4 PATCH matches/{matchId}）。
 *
 * <p>{@code rosterDeadline=null} を渡すと締切なし（ロック解除）になる。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateFixtureRosterDeadlineRequest {

    /** 提出締切（NULL=締切なし） */
    private LocalDateTime rosterDeadline;
}
