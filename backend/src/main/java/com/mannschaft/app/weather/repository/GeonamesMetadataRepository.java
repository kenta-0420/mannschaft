package com.mannschaft.app.weather.repository;

import com.mannschaft.app.weather.entity.GeonamesMetadataEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * GeoNames 取り込みメタデータ用リポジトリ。
 *
 * <p>シングルトン例外（CLAUDE.md 原則 6 のシングルトン例外条項）のため、
 * 通常は {@code findById((short) 1)} で唯一のレコードを取得する。</p>
 */
@Repository
public interface GeonamesMetadataRepository extends JpaRepository<GeonamesMetadataEntity, Short> {
}
