package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * エントリー表メンバー一覧レスポンスDTO。
 *
 * <p>F08.7 Phase 9: チームのエントリー表と、必要に応じてチームメンバー候補を返す。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryMemberListResponse {

    /** エントリー済みメンバー一覧（sort_order順） */
    List<EntryMemberResponse> entryMembers;

    /**
     * チームメンバー候補一覧（includeTeamMembers=false の場合はnull）。
     * エントリー可能な全チームメンバーと、既エントリー済みフラグを返す。
     */
    List<TeamMemberCandidateResponse> teamMemberCandidates;

    /** 現在のエントリー人数 */
    int entryCount;

    /** 最小エントリー人数（nullable: ディビジョン未設定の場合） */
    Integer minEntryCount;

    /** 最大エントリー人数（nullable: ディビジョン未設定の場合） */
    Integer maxEntryCount;
}
