package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * エントリーサマリー1チーム分のレスポンスDTO。
 *
 * <p>F08.7 Phase 9: 主催者向けの全チームエントリーサマリーに含まれる1行。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryMemberSummaryItemResponse {

    /** 参加チームID（tournament_participants.id） */
    Long participantId;

    /** チームID */
    Long teamId;

    /** チーム表示名 */
    String displayName;

    /** エントリー人数 */
    long entryCount;

    /** 最小エントリー人数を満たしているか */
    boolean isMinMet;

    /** 最大エントリー人数を超過しているか */
    boolean isMaxExceeded;

    /** 最終更新日時（エントリーが1件もない場合はnull） */
    LocalDateTime lastUpdatedAt;
}
