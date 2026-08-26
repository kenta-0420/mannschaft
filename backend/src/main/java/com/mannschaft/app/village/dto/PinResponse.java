package com.mannschaft.app.village.dto;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * F17.1 B8 — お気に入り村ピン留め 1 件のレスポンス DTO（設計書 §4.8）。
 *
 * <p>個人ダッシュボードの「お気に入り村フィード」ウィジェットや一覧画面で
 * 表示するため、村側のサムネ情報を同梱する（N+1 を避けるため Service 層で 1 クエリ join）。</p>
 *
 * @param id              ピンエンティティ ID
 * @param villageId       村 ID
 * @param villageName     村名（村側 join）
 * @param villageIconUrl  村アイコンの表示用署名付き URL（{@code MediaUrlResolver} で解決済み。
 *                        FE は img src に直接渡す。アイコン未設定/解決不能時は null）
 * @param sortOrder       並び順（小さいほど上）
 * @param pinnedAt        ピン留め日時
 */
@Builder
public record PinResponse(
        UUID id,
        UUID villageId,
        String villageName,
        String villageIconUrl,
        long sortOrder,
        LocalDateTime pinnedAt
) {}
