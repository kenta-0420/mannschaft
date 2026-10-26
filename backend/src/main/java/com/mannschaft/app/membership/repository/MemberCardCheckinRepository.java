package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.entity.MemberCardCheckinEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * チェックイン履歴リポジトリ。
 */
public interface MemberCardCheckinRepository extends JpaRepository<MemberCardCheckinEntity, Long> {

    /**
     * 最新のチェックイン日時を取得する（二重スキャン防止用）。
     */
    Optional<MemberCardCheckinEntity> findTopByMemberCardIdOrderByCheckedInAtDesc(Long memberCardId);

    /**
     * 会員証のチェックイン履歴を取得する。
     */
    List<MemberCardCheckinEntity> findByMemberCardIdAndCheckedInAtBetweenOrderByCheckedInAtDesc(
            Long memberCardId, LocalDateTime from, LocalDateTime to);

    /**
     * スコープ内の全チェックイン履歴を取得する（チーム/組織全体）。
     */
    @Query("SELECT c FROM MemberCardCheckinEntity c JOIN MemberCardEntity mc ON c.memberCardId = mc.id " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND c.checkedInAt BETWEEN :from AND :to ORDER BY c.checkedInAt DESC")
    List<MemberCardCheckinEntity> findByScopeAndPeriod(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 拠点の当日チェックイン件数を取得する。
     */
    long countByCheckinLocationIdAndCheckedInAtAfter(Long checkinLocationId, LocalDateTime after);

    /**
     * 統計: スコープ内のチェックイン総数を取得する。
     */
    @Query("SELECT COUNT(c) FROM MemberCardCheckinEntity c JOIN MemberCardEntity mc ON c.memberCardId = mc.id " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND c.checkedInAt BETWEEN :from AND :to")
    long countByScopeAndPeriod(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 統計: ユニークメンバー数を取得する。
     */
    @Query("SELECT COUNT(DISTINCT c.memberCardId) FROM MemberCardCheckinEntity c " +
            "JOIN MemberCardEntity mc ON c.memberCardId = mc.id " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND c.checkedInAt BETWEEN :from AND :to")
    long countUniqueMembersByScopeAndPeriod(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 統計: チェックイン種別ごとの件数を取得する。
     */
    @Query("SELECT c.checkinType, COUNT(c) FROM MemberCardCheckinEntity c " +
            "JOIN MemberCardEntity mc ON c.memberCardId = mc.id " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND c.checkedInAt BETWEEN :from AND :to GROUP BY c.checkinType")
    List<Object[]> countByCheckinTypeByScopeAndPeriod(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 統計: 曜日別チェックイン件数を取得する。
     */
    @Query(value = "SELECT DAYOFWEEK(c.checked_in_at) AS dow, COUNT(*) AS cnt " +
            "FROM member_card_checkins c JOIN member_cards mc ON c.member_card_id = mc.id " +
            "WHERE mc.scope_type = :scopeType AND mc.scope_id = :scopeId " +
            "AND c.checked_in_at BETWEEN :from AND :to GROUP BY dow ORDER BY dow",
            nativeQuery = true)
    List<Object[]> countByDayOfWeek(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 統計: 時間帯別チェックイン件数を取得する。
     */
    @Query(value = "SELECT HOUR(c.checked_in_at) AS h, COUNT(*) AS cnt " +
            "FROM member_card_checkins c JOIN member_cards mc ON c.member_card_id = mc.id " +
            "WHERE mc.scope_type = :scopeType AND mc.scope_id = :scopeId " +
            "AND c.checked_in_at BETWEEN :from AND :to GROUP BY h ORDER BY h",
            nativeQuery = true)
    List<Object[]> countByHour(
            @Param("scopeType") String scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 統計: 来店頻度トップ10を取得する。
     */
    @Query("SELECT c.memberCardId, COUNT(c) AS cnt FROM MemberCardCheckinEntity c " +
            "JOIN MemberCardEntity mc ON c.memberCardId = mc.id " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND c.checkedInAt BETWEEN :from AND :to " +
            "GROUP BY c.memberCardId ORDER BY cnt DESC")
    List<Object[]> findTopMembersByScopeAndPeriod(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * 統計: 場所別チェックイン件数を取得する。
     */
    @Query("SELECT COALESCE(c.location, 'unknown'), c.checkinType, COUNT(c) FROM MemberCardCheckinEntity c " +
            "JOIN MemberCardEntity mc ON c.memberCardId = mc.id " +
            "WHERE mc.scopeType = :scopeType AND mc.scopeId = :scopeId " +
            "AND c.checkedInAt BETWEEN :from AND :to " +
            "GROUP BY c.location, c.checkinType ORDER BY COUNT(c) DESC")
    List<Object[]> countByLocationByScopeAndPeriod(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
