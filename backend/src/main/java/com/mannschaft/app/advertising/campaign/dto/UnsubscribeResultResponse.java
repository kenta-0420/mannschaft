package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;

import java.util.List;

/**
 * F09.17 残課題 4 — 公開 unsubscribe SPA への POST レスポンス DTO。
 *
 * <p>SPA は受信した {@code disabledChannels} / {@code remainingActiveChannels} を画面に
 * 反映し、{@code messageKey} を i18n キーとして文言を組み立てる。
 * フロントエンドが直接表示する文言はクライアント側 i18n でローカライズするため、
 * サーバーは「表示文言」ではなく「キー」を返す方針とする。</p>
 *
 * @param disabledChannels         今回 OFF にしたチャネル一覧
 * @param remainingActiveChannels  まだ ON のままのチャネル一覧（情報提供用）
 * @param messageKey               フロントエンド i18n キー (例:
 *                                 {@code "advertising.unsubscribe_spa.success_message"})
 */
public record UnsubscribeResultResponse(
        List<AdChannelType> disabledChannels,
        List<AdChannelType> remainingActiveChannels,
        String messageKey) {
}
