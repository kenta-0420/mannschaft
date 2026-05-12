package com.mannschaft.app.tournament.entry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 大会エントリーメンバーリポジトリ。
 */
public interface TournamentEntryMemberRepository
        extends JpaRepository<TournamentEntryMemberEntity, UUID> {

    /**
     * 参加チームIDに紐づくエントリーメンバーを並び順で取得する。
     */
    List<TournamentEntryMemberEntity> findByParticipantIdOrderBySortOrderAsc(Long participantId);

    /**
     * 参加チームIDに紐づく全エントリーメンバーを削除する。
     */
    void deleteAllByParticipantId(Long participantId);

    /**
     * 指定の参加チームに対して指定ユーザーが既にエントリー済みか確認する。
     */
    boolean existsByParticipantIdAndUserId(Long participantId, Long userId);

    /**
     * 参加チームIDに紐づくエントリー済みユーザーIDセットを取得する。
     */
    @Query("SELECT e.userId FROM TournamentEntryMemberEntity e WHERE e.participantId = :participantId")
    Set<Long> findUserIdsByParticipantId(@Param("participantId") Long participantId);

    /**
     * IDと参加チームIDの組み合わせでエントリーメンバーを取得する（IDOR対策）。
     */
    Optional<TournamentEntryMemberEntity> findByIdAndParticipantId(UUID id, Long participantId);

    /**
     * 複数の参加チームIDに対するエントリー数を一括集計する（N+1対策）。
     */
    @Query("SELECT e.participantId AS participantId, COUNT(e) AS entryCount, MAX(e.updatedAt) AS lastUpdatedAt " +
           "FROM TournamentEntryMemberEntity e WHERE e.participantId IN :participantIds " +
           "GROUP BY e.participantId")
    List<EntryCountProjection> countByParticipantIds(@Param("participantIds") List<Long> participantIds);

    /**
     * エントリー数集計用プロジェクション。
     */
    interface EntryCountProjection {
        Long getParticipantId();
        Long getEntryCount();
        LocalDateTime getLastUpdatedAt();
    }
}
