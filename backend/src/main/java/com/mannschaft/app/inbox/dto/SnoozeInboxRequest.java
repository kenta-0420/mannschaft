package com.mannschaft.app.inbox.dto;

import com.mannschaft.app.inbox.InboxSourceType;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.11 統合通知インボックス：スヌーズリクエスト DTO。
 *
 * <p>既存 {@code notification/dto/SnoozeRequest} に倣い {@code snoozedUntil}（絶対時刻・ISO8601）を採用する
 * （フロントがプリセットから日時を計算して送る。既存 snooze の duration 送信バグの是正方針）。
 * 設計書: 02_api_design.md §3.3 / 03_business_logic.md §6。</p>
 */
@Getter
@RequiredArgsConstructor
public class SnoozeInboxRequest {

    /** 通知ソース種別 */
    @NotNull
    private final InboxSourceType sourceType;

    /** 各ソース PK */
    @NotNull
    private final Long sourceId;

    /** スヌーズ解除予定時刻（未来必須） */
    @NotNull
    @Future
    private final LocalDateTime snoozedUntil;
}
