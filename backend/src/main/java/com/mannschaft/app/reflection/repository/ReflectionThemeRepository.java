package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ReflectionThemeEntity} のリポジトリ（F06.5・§2.0）。
 *
 * <p>個人所有で organization_id を持たないため {@code AbstractTenantAwareRepository} は適用しない。</p>
 */
@Repository
public interface ReflectionThemeRepository extends JpaRepository<ReflectionThemeEntity, UUID> {

    /** 自分のテーマ一覧（論理削除除外は {@code @SQLRestriction} が担保）。 */
    List<ReflectionThemeEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 本人所有検証用（id＋user_id）。 */
    Optional<ReflectionThemeEntity> findByIdAndUserId(UUID id, Long userId);

    /** テーマ数上限（§2.5.1 (b)・100）判定用。 */
    long countByUserId(Long userId);
}
