package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ユーザー×カレンダーレイヤー表示設定リポジトリ（F03.19）。
 *
 * <p>設計書 §4.3〜4.5 の口に対応する finder のみを定義する。
 * 本表は本人のみが読み書きできる個人設定であり（{@code user_id} が常に呼び出し本人と一致することを
 * Service 層が保証する）、{@code AbstractTenantAwareRepository} の適用対象外（設計書 §3.1）。</p>
 */
public interface UserCalendarLayerSettingRepository extends JpaRepository<UserCalendarLayerSettingEntity, UUID> {

    /** §4.3 レイヤー一覧の合成に使う、本人の設定行の全件取得。 */
    List<UserCalendarLayerSettingEntity> findByUserId(Long userId);

    /** §4.4/4.5 の upsert・削除キー（{@code uk_user_calendar_layer} と同一）。 */
    Optional<UserCalendarLayerSettingEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, String scopeType, Long scopeId);

    /** §4.5 DELETE（冪等・物理削除）。 */
    void deleteByUserIdAndScopeTypeAndScopeId(Long userId, String scopeType, Long scopeId);

    /** §4.4 の行数上限（1000件未満）チェック用。 */
    long countByUserId(Long userId);
}
