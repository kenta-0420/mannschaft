package com.mannschaft.app.tournament.fee.dto;

import com.mannschaft.app.tournament.fee.TournamentFeeEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 大会参加費レスポンス DTO（F08.7.1/07）。
 *
 * <p>金額・通貨は表示の利便のため F08.2 の payment_item から充填する（出所は payment_item・本テーブルは持たない）。</p>
 *
 * @param id            参加費 ID（UUIDv7）
 * @param tournamentId  対象大会 ID
 * @param divisionId    対象ディビジョン ID（NULL = 大会全体）
 * @param paymentItemId 連結 payment_item ID
 * @param title         表示名
 * @param targetScope   対象範囲（ALL_TEAMS / SPECIFIC_TEAMS）
 * @param targetTeamIds SPECIFIC_TEAMS のときの対象チーム一覧（ALL_TEAMS では空）
 * @param amount        金額（payment_item 由来）
 * @param currency      通貨（payment_item 由来）
 * @param paymentDue    支払期限
 * @param createdAt     作成日時
 */
public record TournamentFeeResponse(
        UUID id,
        Long tournamentId,
        Long divisionId,
        Long paymentItemId,
        String title,
        String targetScope,
        List<Long> targetTeamIds,
        BigDecimal amount,
        String currency,
        LocalDateTime paymentDue,
        LocalDateTime createdAt
) {
    /**
     * エンティティ・対象チーム・金額情報からレスポンスを組み立てる。
     */
    public static TournamentFeeResponse of(TournamentFeeEntity fee, List<Long> targetTeamIds,
                                           BigDecimal amount, String currency) {
        return new TournamentFeeResponse(
                fee.getId(),
                fee.getTournamentId(),
                fee.getDivisionId(),
                fee.getPaymentItemId(),
                fee.getTitle(),
                fee.getTargetScope().name(),
                targetTeamIds,
                amount,
                currency,
                fee.getPaymentDue(),
                fee.getCreatedAt()
        );
    }
}
