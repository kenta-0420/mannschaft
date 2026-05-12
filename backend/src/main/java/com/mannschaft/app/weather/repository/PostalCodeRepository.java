package com.mannschaft.app.weather.repository;

import com.mannschaft.app.weather.entity.PostalCodeEntity;
import com.mannschaft.app.weather.entity.PostalCodeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * GeoNames 郵便番号マスタ用リポジトリ。
 *
 * <p>マスタ例外として {@code organization_id} を持たないため、
 * {@code AbstractTenantAwareRepository}（原則 7）は適用対象外。素の
 * {@link JpaRepository} を継承する。</p>
 */
@Repository
public interface PostalCodeRepository extends JpaRepository<PostalCodeEntity, PostalCodeId> {

    /**
     * 国コードと郵便番号で該当する地点マスタを 1 件取得する。
     *
     * @param countryCode ISO 3166-1 alpha-2
     * @param postalCode  国別フォーマットの郵便番号
     * @return ヒットした地点マスタ（存在しなければ空）
     */
    Optional<PostalCodeEntity> findByCountryCodeAndPostalCode(String countryCode, String postalCode);
}
