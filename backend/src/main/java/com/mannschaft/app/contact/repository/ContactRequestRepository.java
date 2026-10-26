package com.mannschaft.app.contact.repository;

import com.mannschaft.app.contact.entity.ContactRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 連絡先申請リポジトリ。
 */
public interface ContactRequestRepository extends JpaRepository<ContactRequestEntity, Long> {

    /** 受信申請一覧（PENDING のみ） */
    List<ContactRequestEntity> findByTargetIdAndStatusOrderByCreatedAtDesc(Long targetId, String status);

    /** 送信済み申請一覧（PENDING のみ） */
    List<ContactRequestEntity> findByRequesterIdAndStatusOrderByCreatedAtDesc(Long requesterId, String status);

    /** 同一ペアの特定ステータスの申請を取得 */
    Optional<ContactRequestEntity> findByRequesterIdAndTargetIdAndStatus(Long requesterId, Long targetId, String status);

    /** 双方向でACCEPTEDの関係が存在するか（連絡先チェック） */
    @Query("SELECT COUNT(r) > 0 FROM ContactRequestEntity r " +
           "WHERE r.status = 'ACCEPTED' AND (" +
           "  (r.requesterId = :userA AND r.targetId = :userB) OR " +
           "  (r.requesterId = :userB AND r.targetId = :userA)" +
           ")")
    boolean isContact(@Param("userA") Long userA, @Param("userB") Long userB);

    /** REJECTED後の再申請クールダウンチェック（72時間） */
    @Query("SELECT COUNT(r) > 0 FROM ContactRequestEntity r " +
           "WHERE r.requesterId = :requesterId AND r.targetId = :targetId " +
           "AND r.status = 'REJECTED' AND r.respondedAt > :since")
    boolean hasRecentRejection(@Param("requesterId") Long requesterId,
                               @Param("targetId") Long targetId,
                               @Param("since") LocalDateTime since);

    /** 24時間以内の同一相手への申請チェック */
    @Query("SELECT COUNT(r) > 0 FROM ContactRequestEntity r " +
           "WHERE r.requesterId = :requesterId AND r.targetId = :targetId " +
           "AND r.createdAt > :since")
    boolean hasRecentRequest(@Param("requesterId") Long requesterId,
                             @Param("targetId") Long targetId,
                             @Param("since") LocalDateTime since);

    /** AUTO_TEAM/AUTO_ORG の再申請スパム対策チェック（直近30日） */
    @Query("SELECT COUNT(r) > 0 FROM ContactRequestEntity r " +
           "WHERE ((r.requesterId = :userA AND r.targetId = :userB) OR (r.requesterId = :userB AND r.targetId = :userA)) " +
           "AND r.sourceType IN ('AUTO_TEAM', 'AUTO_ORG') " +
           "AND r.status IN ('REJECTED', 'CANCELLED') " +
           "AND r.updatedAt > :since")
    boolean hasRecentAutoRejection(@Param("userA") Long userA,
                                   @Param("userB") Long userB,
                                   @Param("since") LocalDateTime since);
}
