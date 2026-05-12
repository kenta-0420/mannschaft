package com.mannschaft.app.tournament.entry.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * エントリー表メンバー1件のレスポンスDTO。
 *
 * <p>F08.7 Phase 9: 大会エントリー表機能</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntryMemberResponse {

    /** エントリーメンバーID（UUIDv7） */
    UUID id;

    /** 参加チームID（tournament_participants.id） */
    Long participantId;

    /** ユーザーID */
    Long userId;

    /**
     * 表示名（UserRepository.MemberSummary から解決）。
     * TODO: 将来的に専用のUserQueryServiceで一括解決すること
     */
    String displayName;

    /** チームメンバー番号（nullable） */
    String memberNumber;

    /** ポジション（nullable） */
    String position;

    /** 背番号（nullable） */
    Integer jerseyNumber;

    /** 備考（nullable） */
    String notes;

    /** 並び順 */
    Short sortOrder;

    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
