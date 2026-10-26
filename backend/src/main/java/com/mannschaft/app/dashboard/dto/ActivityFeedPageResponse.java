package com.mannschaft.app.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アクティビティフィードの1ページ分のレスポンス（F03.18 §4.1）。
 *
 * <p>従来 {@code GET /dashboard/activity} は配列を直返ししており、次ページの起点を返す場所が
 * どこにも無かった。可視性フィルタ（§4.2）は取得済みの行を後段で間引くため、
 * 「返した件数」と「次にどこから読むべきか」が一致しなくなる。そこで本ラッパー型を新設し、
 * {@code nextCursor} を明示的に返す。<strong>破壊的変更</strong>（マスター裁可済み）。</p>
 *
 * <p>{@code nextCursor} は <b>フィルタ前に Repository から取得した最終行の id</b> を文字列化した
 * ものである。フィルタ後の id を使うと、次回リクエストが「除外済みの行を読み直す」区間を含み、
 * カーソル条件（{@code a.id < :cursor}）との整合が崩れる。</p>
 *
 * <p>データが尽きた場合は {@code null}。追加フェッチの上限に達して打ち切った場合は
 * 「まだ続きがある」ことを表すため非 null を返す。</p>
 */
@Getter
@RequiredArgsConstructor
@Schema(description = "アクティビティフィードの1ページ")
public class ActivityFeedPageResponse {

    @Schema(description = "このページのアクティビティ一覧")
    private final List<ActivityFeedResponse> items;

    /**
     * 次ページの起点。クライアントは次回リクエストの {@code cursor} にこの値を渡す。
     * JSON 上は文字列（既存 FE の {@code cursor} 受け渡しが文字列であるため型を揃える）。
     */
    @Schema(type = "string", nullable = true,
            description = "次ページの起点となるアクティビティID（文字列）。続きが無い場合は null")
    private final String nextCursor;

    /**
     * 空ページ（該当なし・続きなし）を返す。
     */
    public static ActivityFeedPageResponse empty() {
        return new ActivityFeedPageResponse(List.of(), null);
    }
}
