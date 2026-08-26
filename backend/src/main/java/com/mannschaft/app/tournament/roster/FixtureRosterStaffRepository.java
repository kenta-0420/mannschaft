package com.mannschaft.app.tournament.roster;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

/**
 * ベンチ入り役員リポジトリ（F08.7.1/05 §8.3）。
 */
public interface FixtureRosterStaffRepository extends JpaRepository<FixtureRosterStaffEntity, UUID> {

    /** 試合×参加チーム単位でベンチ役員一覧を取得する */
    List<FixtureRosterStaffEntity> findByMatchIdAndParticipantIdOrderByCreatedAtAsc(Long matchId, Long participantId);

    /** 試合の全参加チームのベンチ役員一覧を取得する（主催者ビュー用） */
    List<FixtureRosterStaffEntity> findByMatchIdOrderByParticipantIdAscCreatedAtAsc(Long matchId);

    /** 自チーム分のベンチ役員を全削除する（提出時の全置換 UPSERT に使用） */
    @Modifying
    void deleteByMatchIdAndParticipantId(Long matchId, Long participantId);
}
