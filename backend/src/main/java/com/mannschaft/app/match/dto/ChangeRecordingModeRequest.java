package com.mannschaft.app.match.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 記録モード切替リクエスト（公式戦⇔共同記録・作成者/主体チーム ADMIN のみ・03 §C.3）。
 *
 * <p>{@code hasScorekeeper=true}（公式戦）時は {@code scorekeeperUserId} に記録係を指定する。
 * {@code false}（共同記録）時は記録係を解除する（Service が null 化）。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.3</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class ChangeRecordingModeRequest {

    /** true=公式戦（記録係単独入力）/ false=共同記録。 */
    private boolean hasScorekeeper;

    /** 記録係ユーザー（公式戦時・user ドメイン ID 参照）。 */
    private Long scorekeeperUserId;
}
