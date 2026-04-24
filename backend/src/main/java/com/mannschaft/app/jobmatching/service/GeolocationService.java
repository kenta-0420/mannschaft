package com.mannschaft.app.jobmatching.service;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * F13.1 Phase 13.1.2 — Geolocation（緯度経度）補助サービス。
 *
 * <p>QR チェックイン／アウト時に Worker 端末から取得した位置情報と業務場所との乖離を
 * Haversine 公式で算出し、閾値を超えた場合に {@code geo_anomaly=TRUE} を立てるための
 * 計算ロジックを提供する（設計書 §2.3.1 / §10.10）。</p>
 *
 * <p>本サービスは純計算のみを担い、閾値の取得や永続化には関与しない（呼び出し側で
 * {@link com.mannschaft.app.jobmatching.config.QrSigningProperties#getAnomalyDistanceMeters()}
 * などを参照する）。</p>
 *
 * <h3>判定方針（設計書 §10.10）</h3>
 * <ul>
 *   <li>業務場所または端末位置の緯度経度いずれかが {@code null} の場合は距離判定自体をスキップ
 *       （{@link #distanceMeters(Double, Double, Double, Double)} が {@link Optional#empty()} を返す）</li>
 *   <li>GPS 精度（accuracy）が 100 m を超える場合は乖離判定をスキップ（精度低下時の誤検出回避）</li>
 *   <li>距離が閾値（デフォルト 500 m）を超えた場合に {@code geo_anomaly=TRUE}</li>
 *   <li>自動拒否はせず、チェックイン自体は成立させた上で Requester へアラート通知</li>
 * </ul>
 */
@Component
public class GeolocationService {

    /** accuracy 閾値（メートル）。この値を超える精度のときは乖離判定をスキップする。 */
    static final double ACCURACY_SKIP_THRESHOLD_METERS = 100.0;

    /** 地球半径（メートル、平均値）。Haversine 距離計算に使用する。 */
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * 2 点間の Haversine 距離（メートル）を計算する。
     *
     * <p>いずれかの緯度経度パラメータが {@code null} の場合、業務場所が未登録 or 端末位置未取得と
     * 判断し {@link Optional#empty()} を返す（呼び出し側で乖離判定をスキップする）。</p>
     *
     * @param lat1 業務場所の緯度（null の場合 empty）
     * @param lng1 業務場所の経度（null の場合 empty）
     * @param lat2 端末位置の緯度（null の場合 empty）
     * @param lng2 端末位置の経度（null の場合 empty）
     * @return メートル単位の距離（両端で null が無ければ非 empty）
     */
    public Optional<Double> distanceMeters(Double lat1, Double lng1, Double lat2, Double lng2) {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) {
            return Optional.empty();
        }
        double lat1Rad = Math.toRadians(lat1);
        double lat2Rad = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLng = Math.toRadians(lng2 - lng1);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1Rad) * Math.cos(lat2Rad)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Optional.of(EARTH_RADIUS_METERS * c);
    }

    /**
     * 距離と GPS 精度から {@code geo_anomaly} フラグを立てるべきか判定する。
     *
     * <ul>
     *   <li>{@code distance} が {@code null} のときは {@code false}（距離計算が不可の場合、
     *       乖離判定できないため false を返す）</li>
     *   <li>{@code accuracy} が非 null かつ {@link #ACCURACY_SKIP_THRESHOLD_METERS}（100 m）を超える場合は
     *       GPS 精度が低いため判定をスキップし {@code false} を返す</li>
     *   <li>それ以外は {@code distance > thresholdMeters} を評価して返す</li>
     * </ul>
     *
     * @param distance         業務場所からの距離（メートル、null 可）
     * @param accuracy         端末取得時の GPS 精度（メートル、null 可 = 不明）
     * @param thresholdMeters  判定閾値（メートル、通常 500 m）
     * @return 乖離フラグを立てるべき場合 true
     */
    public boolean isAnomaly(Double distance, Double accuracy, double thresholdMeters) {
        if (distance == null) {
            return false;
        }
        if (accuracy != null && accuracy > ACCURACY_SKIP_THRESHOLD_METERS) {
            return false;
        }
        return distance > thresholdMeters;
    }
}
