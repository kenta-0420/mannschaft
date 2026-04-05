package com.mannschaft.app.seal.repository;

import com.mannschaft.app.seal.StampTargetType;
import com.mannschaft.app.seal.entity.SealStampLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 押印ログリポジトリ。
 */
public interface SealStampLogRepository extends JpaRepository<SealStampLogEntity, Long> {

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
