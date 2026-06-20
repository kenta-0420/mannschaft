package com.mannschaft.app.weather.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * 既存ユーザー初回導出ジョブ（{@code WeatherLocationBootstrapJob}）の冪等フラグエンティティ。
 *
 * <p>主キー方針: シングルトン例外（CLAUDE.md 原則 6 のシングルトン例外条項）。
 * {@code CHECK (id = 1)} で行数 1 を強制。</p>
 *
 * <p>初回起動時に {@code INSERT IGNORE} で行を作成し、ジョブ実行完了時に
 * {@code completed_at} を UPDATE する設計。</p>
 */
@Entity
@Table(name = "weather_location_bootstrap_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class WeatherLocationBootstrapJobEntity {

    /** 固定値 1（シングルトン制約）。 */
    @Id
    @Column(name = "id", columnDefinition = "TINYINT UNSIGNED")
    private Short id;

    /** ジョブ完了日時。NULL のときは未完了。 */
    @Setter
    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 処理済みユーザー数。 */
    @Setter
    @Column(name = "processed_user_count")
    private Long processedUserCount;

    /** スキップユーザー数（郵便番号未ヒット等）。 */
    @Setter
    @Column(name = "skipped_user_count")
    private Long skippedUserCount;
}
