package com.mannschaft.app.weather.repository;

import com.mannschaft.app.weather.entity.WeatherLocationBootstrapJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 既存ユーザー初回導出ジョブの冪等フラグ用リポジトリ。
 *
 * <p>シングルトン例外（CLAUDE.md 原則 6 のシングルトン例外条項）のため、
 * 通常は {@code findById((short) 1)} で唯一のレコードを取得する。
 * {@code Flyway V66.005} で初期行を INSERT IGNORE で投入済み。</p>
 */
@Repository
public interface WeatherLocationBootstrapJobRepository extends JpaRepository<WeatherLocationBootstrapJobEntity, Short> {
}
