package com.mannschaft.app.match.dto;

import com.mannschaft.app.match.domain.MatchEventType;
import com.mannschaft.app.match.domain.PeriodType;
import com.mannschaft.app.match.domain.TeamSide;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * タイムラインイベントの記録/更新リクエスト（03 §C.4a / §C.4b）。
 *
 * <p><b>マスアサインメント防止（03 §C.4a）</b>: 権限列 {@code recorded_by_team_id} は<b>本 DTO に含めない</b>。
 * Controller が認証主体の所属チーム（match の HOME/AWAY のうち principal が ADMIN/DEPUTY のチーム）から
 * <b>サーバー導出</b>して {@link com.mannschaft.app.match.service.MatchEventService.EventCommand} へ詰める。
 * クライアントが任意チーム ID を詐称する余地を塞ぐ。</p>
 *
 * <p>入力検証（03 §C.4b）: {@code minute}/{@code stoppageMinute} は 0–150、{@code jerseyNumber} は 0–999、
 * {@code note} は最大 255、{@code customLabel} は最大 64。サニタイズ（制御文字除去・trim・HTML 不可）は
 * Service 層（{@code MatchTextSanitizer}）が二重に行う。</p>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C.4a / §C.4b</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class MatchEventRequest {

    /** 経過分（0–150・null=分不明）。 */
    @Min(0)
    @Max(150)
    private Integer minute;

    /** アディショナルタイム（0–150・null=なし）。 */
    @Min(0)
    @Max(150)
    private Integer stoppageMinute;

    /** ピリオド（必須・前後半／延長／PK 戦のいずれか）。 */
    @NotNull
    private PeriodType period;

    /** イベント種別（必須・競技カタログ整合は Service が検証・03 §C.4b）。 */
    @NotNull
    private MatchEventType eventType;

    /** 警告/退場の標準理由コード（カタログ列挙値・event_type 整合は Service が検証・最大 8）。 */
    @Size(max = 8)
    private String cardReasonCode;

    /** event_type=OTHER 時の自由ラベル名（最大 64・03 §C.4b）。 */
    @Size(max = 64)
    private String customLabel;

    /** チームサイド（必須・HOME/AWAY・03 §C.4a）。 */
    @NotNull
    private TeamSide teamSide;

    /** 主体選手（user ドメイン ID 参照・未登録は null）。 */
    private Long playerUserId;

    @Size(max = 128)
    private String playerName;

    @Min(0)
    @Max(999)
    private Integer jerseyNumber;

    /** 関連選手（アシスト者/交代相手・user ドメイン ID 参照）。 */
    private Long relatedPlayerUserId;

    @Size(max = 128)
    private String relatedPlayerName;

    /** 理由・メモ（最大 255・03 §C.4b）。 */
    @Size(max = 255)
    private String note;

    /** 時系列連鎖の相手イベント（同一 match 帰属は Service が検証・03 §C.4a）。 */
    private UUID linkedEventId;

    /** 拡張属性（競技別の追加情報・JSON 文字列・最大 4KB・03 §C.4b）。 */
    @Size(max = 4096)
    private String detail;

    /** 同分内の表示順（タイムライン安定ソート）。 */
    @Min(0)
    private int sortSeq;
}
