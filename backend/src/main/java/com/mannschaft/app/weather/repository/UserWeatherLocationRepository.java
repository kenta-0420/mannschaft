package com.mannschaft.app.weather.repository;

import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F02.10 ユーザー地点キャッシュ用リポジトリ。
 *
 * <p>個人帰属データ（{@code organization_id} を持たない）のため
 * {@code AbstractTenantAwareRepository}（原則 7）は適用対象外。素の
 * {@link JpaRepository} を継承する。</p>
 */
@Repository
public interface UserWeatherLocationRepository extends JpaRepository<UserWeatherLocationEntity, UUID> {

    /**
     * 指定ユーザーの指定ラベル地点を 1 件取得する。
     *
     * @param userId ユーザー ID
     * @param label  地点ラベル（本機能では {@code "home"} 固定）
     * @return ヒットした地点（存在しなければ空）
     */
    Optional<UserWeatherLocationEntity> findByUserIdAndLabel(Long userId, String label);

    /**
     * 指定ユーザーの全地点を取得する。複数地点拡張時 / GDPR エクスポート用。
     *
     * @param userId ユーザー ID
     * @return 該当する地点リスト
     */
    List<UserWeatherLocationEntity> findByUserId(Long userId);

    /**
     * 指定ユーザーの全地点を物理削除する。退会時 / GDPR 削除要求時に使用。
     *
     * @param userId ユーザー ID
     * @return 削除件数
     */
    @Modifying
    @Query("DELETE FROM UserWeatherLocationEntity uwl WHERE uwl.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
