package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.entity.UserCalendarLayerSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * §10.4【R9】チーム／組織の<b>削除</b>に伴う後始末。
     *
     * <p>当該スコープの設定行を<b>全ユーザー分</b>物理削除する。
     * <b>脱退では呼ばない</b>（脱退時は行を残し、再加入で色が復活する — R9）。</p>
     *
     * @return 削除した行数
     */
    @Modifying
    @Query("DELETE FROM UserCalendarLayerSettingEntity s "
            + "WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    int deleteByScopeTypeAndScopeId(@Param("scopeType") String scopeType,
                                    @Param("scopeId") Long scopeId);

    /**
     * §10.4【R9】ユーザー退会（即時匿名化）に伴う後始末。
     *
     * <p>当該ユーザーの設定行を<b>全スコープ分</b>物理削除する。色設定は個人の嗜好情報であり、
     * 匿名化後のユーザーに紐づけて残す意味が無い。</p>
     *
     * @return 削除した行数
     */
    @Modifying
    @Query("DELETE FROM UserCalendarLayerSettingEntity s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
