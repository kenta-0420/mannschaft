package com.mannschaft.app.advertising.campaign.dto;

import com.mannschaft.app.advertising.campaign.enums.AdChannelType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * F09.17 残課題 4 — 公開 unsubscribe SPA からの POST 受信用 DTO。
 *
 * <p>{@code POST /api/v1/ads/unsubscribe} で受け取る。
 * メール末尾リンクから SPA に遷移したユーザーが、チャネル別チェックボックスで
 * 「停止したいチャネルだけ」を選んでから submit するシナリオを想定している。</p>
 *
 * <p>従来の {@code GET /api/v1/ads/unsubscribe?token=...} は単一チャネル OFF（JWT の
 * {@code ch} クレームに従う）だったが、本 DTO は 1〜N 個のチャネルを明示指定できる。</p>
 *
 * <p>JWT 自体は単一の channel claim を持つが、SPA UX 観点では 4 チャネル全て選べる
 * 必要があるため、サーバー側でチャネル単位に冪等な OFF 切替を順次実施する。</p>
 *
 * @param token    メール末尾リンクで配布される unsubscribe JWT（必須）
 * @param channels OFF にしたいチャネル一覧（最低 1 件必須・許容値は {@link AdChannelType}）
 */
public record UnsubscribeRequest(
        @NotBlank String token,
        @NotEmpty List<AdChannelType> channels) {
}
