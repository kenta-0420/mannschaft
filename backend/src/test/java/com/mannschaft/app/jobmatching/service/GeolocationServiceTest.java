package com.mannschaft.app.jobmatching.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GeolocationService} のユニットテスト。F13.1 Phase 13.1.2。
 *
 * <p>Haversine 距離の計算精度と、accuracy 閾値による乖離判定スキップの動作を検証する。</p>
 */
@DisplayName("GeolocationService 単体テスト")
class GeolocationServiceTest {

    private final GeolocationService service = new GeolocationService();

    @Nested
    @DisplayName("distanceMeters: Haversine 距離計算")
    class DistanceMeters {

        @Test
        @DisplayName("同一地点は距離 0 メートル")
        void 同一地点_0m() {
            Optional<Double> d = service.distanceMeters(35.6586, 139.7454, 35.6586, 139.7454);
            assertThat(d).isPresent();
            assertThat(d.get()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        @DisplayName("東京タワー ↔ 東京駅 の実測距離（約 3.1 km）が誤差 1% 以内")
        void 東京タワー_東京駅_約3100m() {
            // 東京タワー: 35.6586, 139.7454 / 東京駅: 35.6812, 139.7671
            Optional<Double> d = service.distanceMeters(35.6586, 139.7454, 35.6812, 139.7671);
            assertThat(d).isPresent();
            // 実測値おおよそ 3100 m。地球半径 6371km の近似誤差 + 緯度球面モデル誤差で ±30m 許容。
            assertThat(d.get()).isBetween(3000.0, 3300.0);
        }

        @Test
        @DisplayName("約 500 m 離れた地点の距離が 480〜520 m の範囲に収まる（閾値境界の検証）")
        void 閾値500m境界() {
            // 緯度 0.0045 度 ≒ 約 500m（1 度 ≒ 111km）
            Optional<Double> d = service.distanceMeters(35.6586, 139.7454, 35.66310, 139.7454);
            assertThat(d).isPresent();
            assertThat(d.get()).isBetween(480.0, 520.0);
        }

        @Test
        @DisplayName("緯度経度いずれかが null なら empty を返す")
        void null入力_empty() {
            assertThat(service.distanceMeters(null, 139.7454, 35.6812, 139.7671)).isEmpty();
            assertThat(service.distanceMeters(35.6586, null, 35.6812, 139.7671)).isEmpty();
            assertThat(service.distanceMeters(35.6586, 139.7454, null, 139.7671)).isEmpty();
            assertThat(service.distanceMeters(35.6586, 139.7454, 35.6812, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isAnomaly: 乖離判定")
    class IsAnomaly {

        @Test
        @DisplayName("距離が閾値以下なら false（乖離でない）")
        void 閾値以下_false() {
            assertThat(service.isAnomaly(499.0, 10.0, 500.0)).isFalse();
            assertThat(service.isAnomaly(500.0, 10.0, 500.0)).isFalse();
        }

        @Test
        @DisplayName("距離が閾値を超えたら true（乖離と判定）")
        void 閾値超過_true() {
            assertThat(service.isAnomaly(500.001, 10.0, 500.0)).isTrue();
            assertThat(service.isAnomaly(1000.0, 10.0, 500.0)).isTrue();
        }

        @Test
        @DisplayName("accuracy が 100m 超なら判定スキップ（常に false）")
        void accuracy閾値超過_判定スキップ() {
            // distance が大きくても accuracy 低精度で false。
            assertThat(service.isAnomaly(10_000.0, 150.0, 500.0)).isFalse();
            // 境界: 100.0 は含まず（超過のみスキップ）。
            assertThat(service.isAnomaly(10_000.0, 100.0, 500.0)).isTrue();
            // 境界: 100.001 はスキップ。
            assertThat(service.isAnomaly(10_000.0, 100.001, 500.0)).isFalse();
        }

        @Test
        @DisplayName("accuracy が null（不明）でも判定は実施（distance 主体で判断）")
        void accuracy_null_判定継続() {
            assertThat(service.isAnomaly(1000.0, null, 500.0)).isTrue();
            assertThat(service.isAnomaly(100.0, null, 500.0)).isFalse();
        }

        @Test
        @DisplayName("distance が null（計算不可）なら常に false")
        void distance_null_false() {
            assertThat(service.isAnomaly(null, 10.0, 500.0)).isFalse();
            assertThat(service.isAnomaly(null, null, 500.0)).isFalse();
        }
    }
}
