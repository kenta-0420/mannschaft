package com.mannschaft.app.tournament.leaguetransfer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 昇格送り出しリクエスト DTO（F08.7.1 / 03 §6・POST .../league-transfers/promote）。
 *
 * <p>下位 org ADMIN が、最上位ディビジョンの昇格枠チームを上位 org へ DISPATCHED 起票する。
 * 送り先 org は送り出し元の祖先 org から解決する（祖先が複数段ある場合は {@code targetOrganizationId} で明示指定可）。</p>
 */
@Getter
@RequiredArgsConstructor
public class PromoteRequest {

    /** 昇格送り出し対象チーム ID 一覧。 */
    @NotEmpty
    private final List<Long> teamIds;

    /**
     * 送り先 org（祖先 org のうち明示指定するもの）。NULL の場合は直近の親 org を既定とする（§5.3）。
     */
    private final Long targetOrganizationId;

    /** 送り出しメッセージ（NULL 許容）。 */
    @Size(max = 500)
    private final String message;
}
