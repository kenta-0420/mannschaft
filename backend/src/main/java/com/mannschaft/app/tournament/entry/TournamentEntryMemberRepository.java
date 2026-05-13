package com.mannschaft.app.tournament.entry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 大会エントリー表メンバーリポジトリ。
 *
 * <p>F08.7 Phase 9: tournament_entry_members テーブルへのアクセスを提供する。</p>
 */
public interface TournamentEntryMemberRepository extends JpaRepository<TournamentEntryMemberEntity, UUID> {

    /** 参加チームのエントリーメンバー一覧をsort_order順で取得する */
    List<TournamentEntryMemberEntity> findByParticipantIdOrderBySortOrderAsc(Long participantId);

    /** 参加チームのエントリー人数を取得する */
    long countByParticipantId(Long participantId);

    /** 参加チームの全エントリーを削除する（全置換時に使用） */
    @Modifying
    void deleteByParticipantId(Long participantId);

    /** 参加チームにエントリー済みのuserIdセットを取得する */
    @Query("SELECT e.userId FROM TournamentEntryMemberEntity e WHERE e.participantId = :participantId")
    Set<Long> findUserIdsByParticipantId(@Param("participantId") Long participantId);

    /** 参加チームとユーザーIDでエントリーを検索する */
    boolean existsByParticipantIdAndUserId(Long participantId, Long userId);

    /**
     * エントリーカウントProjection（主催者向けサマリー用）。
     * ディビジョン単位で参加チームごとのエントリー数と最終更新日時を集計する。
     */
    @Query("""
            SELECT e.participantId AS participantId,
                   COUNT(e.id) AS entryCount,
                   MAX(e.updatedAt) AS lastUpdatedAt
            FROM TournamentEntryMemberEntity e
            WHERE e.participantId IN :participantIds
            GROUP BY e.participantId
            """)
    List<EntryCountProjection> countByParticipantIdIn(@Param("participantIds") List<Long> participantIds);

    /**
     * エントリーカウントProjectionインターフェース。
     */
    interface EntryCountProjection {
        Long getParticipantId();
        long getEntryCount();
        LocalDateTime getLastUpdatedAt();
    }
}
