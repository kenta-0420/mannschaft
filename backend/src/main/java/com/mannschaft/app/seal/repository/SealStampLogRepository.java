package com.mannschaft.app.seal.repository;

import com.mannschaft.app.seal.StampTargetType;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 押印ログリポジトリ。
 */
public interface SealStampLogRepository extends JpaRepository<SealStampLogEntity, Long> {

    /**
     * ユーザーの押印ログをカーソルページングで取得する（id 降順 = 時系列降順）。
     *
     * <p>id は IDENTITY 採番で時系列に単調増加するため、id 降順をカーソルキーにできる。
     * cursor 指定時は cursor より小さい id（= より古い行）を取得する。
     * targetType / includeRevoked は任意の絞り込み条件。</p>
     *
     * @param userId         ユーザーID
     * @param cursor         カーソル（直前ページ末尾の id）。null の場合は先頭から取得
     * @param targetType     対象種別での絞り込み。null の場合は絞り込まない
     * @param includeRevoked false の場合は取消済み（is_revoked=true）を除外
     * @param pageable       取得件数（size+1 を渡し hasNext 判定に使う）
     * @return 押印ログ（id 降順）
     */
    @Query("""
            SELECT s FROM SealStampLogEntity s
            WHERE s.userId = :userId
              AND (:cursor IS NULL OR s.id < :cursor)
              AND (:targetType IS NULL OR s.targetType = :targetType)
              AND (:includeRevoked = true OR s.isRevoked = false)
            ORDER BY s.id DESC
            """)
    List<SealStampLogEntity> findByUserIdWithCursor(
            @Param("userId") Long userId,
            @Param("cursor") Long cursor,
            @Param("targetType") StampTargetType targetType,
            @Param("includeRevoked") boolean includeRevoked,
            Pageable pageable);

    /**
     * ユーザーの押印ログを押印日時降順で取得する。
     */
    List<SealStampLogEntity> findByUserIdOrderByStampedAtDesc(Long userId);

    /**
     * 対象種別・対象IDで押印ログを取得する。
     */
    List<SealStampLogEntity> findByTargetTypeAndTargetIdOrderByStampedAtDesc(
            StampTargetType targetType, Long targetId);

    /**
     * IDとユーザーIDで押印ログを取得する。
     */
    Optional<SealStampLogEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 特定の印鑑の押印ログ件数を取得する。
     */
    long countBySealId(Long sealId);
}
