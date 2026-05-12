package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 全チームエントリーサマリーレスポンスDTO（主催者用）。
 *
 * <p>F08.7 Phase 9: ディビジョン単位の全チームエントリー状況を一覧表示する際に使用する。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryMemberSummaryResponse {

    /** ディビジョンID */
    Long divisionId;

    /** ディビジョン名 */
    String divisionName;

    /** 最小エントリー人数（nullable） */
    Integer minEntryCount;

    /** 最大エントリー人数（nullable） */
    Integer maxEntryCount;

    /** チーム別エントリーサマリー一覧 */
    List<EntryMemberSummaryItemResponse> summary;
}
