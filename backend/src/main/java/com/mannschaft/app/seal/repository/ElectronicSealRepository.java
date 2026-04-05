package com.mannschaft.app.seal.repository;

import com.mannschaft.app.seal.SealVariant;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 電子印鑑リポジトリ。
 */
public interface ElectronicSealRepository extends JpaRepository<ElectronicSealEntity, Long> {

    /**
     * ユーザーの印鑑一覧を取得する。
     */
    List<ElectronicSealEntity> findByUserIdOrderByCreatedAtAsc(Long userId);

    /**
     * ユーザーIDとバリアントで印鑑を取得する。
     */
    Optional<ElectronicSealEntity> findByUserIdAndVariant(Long userId, SealVariant variant);

    /**
     * ユーザーIDとバリアントの組み合わせが存在するか確認する。
     */
    boolean existsByUserIdAndVariant(Long userId, SealVariant variant);

    /**
     * IDとユーザーIDで印鑑を取得する。
     */
    Optional<ElectronicSealEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * ユーザーの印鑑件数を取得する。
     */
    long countByUserId(Long userId);

    /**
     * 全印鑑を取得する（管理者用一括再生成）。
     */
    List<ElectronicSealEntity> findAllByOrderByUserIdAsc();
}
