package com.mannschaft.app.inbox.dto;

import com.mannschaft.app.inbox.InboxSourceType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F04.11 統合通知インボックス：triage 対象指定リクエスト DTO。
 *
 * <p>archive / unarchive / unsnooze で通知 1 件を {@code (sourceType, sourceId)} の複合論理キーで指定する。
 * 設計書: 02_api_design.md §3.3。</p>
 */
@Getter
@RequiredArgsConstructor
public class TriageTargetRequest {

    /** 通知ソース種別 */
    @NotNull
    private final InboxSourceType sourceType;

    /** 各ソース PK */
    @NotNull
    private final Long sourceId;
}
