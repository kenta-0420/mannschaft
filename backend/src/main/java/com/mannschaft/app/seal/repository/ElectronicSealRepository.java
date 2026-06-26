package com.mannschaft.app.seal.repository;

import com.mannschaft.app.seal.SealVariant;
import com.mannschaft.app.seal.entity.ElectronicSealEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * ユーザーの論理削除済み印鑑を物理削除する。
     *
     * <p>再生成時に論理削除済みレコードがユニーク制約（user_id, variant）に
     * 引っかかる問題を防ぐために使用する。
     * {@code @SQLRestriction} は SELECT に作用するが DELETE には作用しないため、
     * native query で削除対象を明示する。</p>
     *
     * @param userId ユーザーID
     * @return 削除件数
     */
    @Modifying
    @Query(value = "DELETE FROM electronic_seals WHERE user_id = :userId AND deleted_at IS NOT NULL", nativeQuery = true)
    int deleteByUserIdAndDeletedAtIsNotNull(@Param("userId") Long userId);
}
