package com.mannschaft.app.committee.repository;

import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 委員会リポジトリ。
 */
public interface CommitteeRepository extends JpaRepository<CommitteeEntity, Long> {

    /**
     * 組織の委員会一覧をページングで取得する。
     */
    Page<CommitteeEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    /**
     * 組織の委員会一覧をステータスフィルタ付きでページングして取得する。
     */
    Page<CommitteeEntity> findByOrganizationIdAndStatus(Long organizationId, CommitteeStatus status, Pageable pageable);

    /**
     * 同一組織内に同名の委員会が存在するか確認する。
     */
    boolean existsByOrganizationIdAndName(Long organizationId, String name);

    /** 通常の委員会書込みで親行を最初にロックする。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CommitteeEntity c WHERE c.id = :committeeId")
    java.util.Optional<CommitteeEntity> findByIdForUpdate(@Param("committeeId") Long committeeId);

    /**
     * 組織脱退者が現役として所属する委員会を ID 順に悲観ロックして取得する。
     *
     * <p>委員会を先に一定順序でロックしてからメンバー行をロックすることで、複数委員会をまたぐ
     * クリーンアップ同士のロック順を固定する。親行のロックは、委員会メンバー追加時の FK 検査とも
     * 直列化される。</p>
     */
    @Query(value = """
            SELECT c.*
              FROM committees c
             WHERE c.organization_id = :organizationId
               AND EXISTS (
                   SELECT 1
                     FROM committee_members m
                    WHERE m.committee_id = c.id
                      AND m.user_id = :userId
                      AND m.left_at IS NULL
               )
             ORDER BY c.id ASC
             FOR UPDATE
            """, nativeQuery = true)
    List<CommitteeEntity> findActiveCommitteesByOrganizationAndUserForUpdate(
            @Param("organizationId") Long organizationId,
            @Param("userId") Long userId);
}
