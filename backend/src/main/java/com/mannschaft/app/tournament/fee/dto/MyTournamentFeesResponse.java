package com.mannschaft.app.tournament.fee.dto;

import java.util.List;

/**
 * 認証ユーザーの大会参加費一覧レスポンス（F08.7.1 Connect 決済）。
 */
public record MyTournamentFeesResponse(
        List<MyTournamentFeeItem> fees
) {
}
