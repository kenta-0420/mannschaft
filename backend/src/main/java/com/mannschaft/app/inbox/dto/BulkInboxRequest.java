package com.mannschaft.app.inbox.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F04.11 統合通知インボックス：一括操作リクエスト DTO。
 *
 * <p>設計書: 02_api_design.md §3.5。複数通知に対する triage / ラベル付与を一括適用する。
 * 部分失敗を許容し、全体は 200 を返す（成功/スキップ件数を {@link BulkResultResponse} で返す）。</p>
 *
 * <ul>
 *   <li>{@code ARCHIVE} / {@code UNARCHIVE}: 各 item をアーカイブ／解除</li>
 *   <li>{@code SNOOZE}: {@code snoozedUntil} 同梱で各 item をスヌーズ</li>
 *   <li>{@code LABEL_ADD}: {@code labelId} 同梱で各 item にラベル付与</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public class BulkInboxRequest {

    /** 一括操作の種別 */
    public enum BulkAction {
        ARCHIVE,
        UNARCHIVE,
        SNOOZE,
        LABEL_ADD
    }

    /** 一括操作の種別（必須） */
    @NotNull
    private final BulkAction action;

    /** 対象通知（1〜50 件） */
    @NotNull
    @Size(min = 1, max = 50)
    @Valid
    private final List<TriageTargetRequest> items;

    /** SNOOZE 時のスヌーズ解除時刻（絶対時刻・ISO8601・オフセット必須／action=SNOOZE のとき必須・サービス層検証） */
    private final OffsetDateTime snoozedUntil;

    /** LABEL_ADD 時の付与ラベル ID（action=LABEL_ADD のとき必須・サービス層検証） */
    private final UUID labelId;
}
