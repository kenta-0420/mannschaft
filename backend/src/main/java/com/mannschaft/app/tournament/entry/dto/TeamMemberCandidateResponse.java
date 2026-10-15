package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * チームメンバー候補（エントリー可能なメンバー）のレスポンスDTO。
 *
 * <p>F08.7 Phase 9: includeTeamMembers=true の場合に EntryMemberListResponse に含まれる。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamMemberCandidateResponse {

    /** ユーザーID */
    Long userId;

    /** 表示名 */
    String displayName;

    /** チームメンバー番号（nullable） */
    String memberNumber;

    /** ポジション（nullable） */
    String position;

    /** 既にエントリー済みかどうか */
    boolean isAlreadyEntered;
}
