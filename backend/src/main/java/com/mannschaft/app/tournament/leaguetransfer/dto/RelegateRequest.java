package com.mannschaft.app.tournament.leaguetransfer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 降格送り出しリクエスト DTO（F08.7.1 / 03 §6・POST .../league-transfers/relegate）。
 *
 * <p>上位 org ADMIN が、最下位ディビジョンの降格枠チームを各チームの出身県協会へ DISPATCHED 起票する。
 * 送り先（出身県協会）は team の ACTIVE 所属のうち送り出し元の子孫 ASSOCIATION を解決して特定する（§5.2）。</p>
 */
@Getter
@RequiredArgsConstructor
public class RelegateRequest {

    /** 降格送り出し対象チーム ID 一覧。 */
    @NotEmpty
    private final List<Long> teamIds;

    /** 送り出しメッセージ（NULL 許容）。 */
    @Size(max = 500)
    private final String message;
}
