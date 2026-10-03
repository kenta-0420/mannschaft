package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * F02.5 ユーザー別 行動メモ設定リポジトリ。
 *
 * <p>PK = userId。{@code findById(userId)} で取得する。
 * レコード未作成のユーザーは {@link com.mannschaft.app.actionmemo.service.ActionMemoSettingsService}
 * 側で「デフォルト値（mood_enabled = false）」と等価に扱う。</p>
 */
public interface UserActionMemoSettingsRepository extends JpaRepository<UserActionMemoSettingsEntity, Long> {

    /**
     * mood 有効カウント取得（メトリクス gauge 用）。
     */
    long countByMoodEnabledTrue();

    /**
     * ユーザー ID で明示取得（可読性のため）。
     */
    default Optional<UserActionMemoSettingsEntity> findByUserId(Long userId) {
        return findById(userId);
    }

    /**
     * リマインド有効かつ時刻設定済みの設定を全件取得する（バッチ用）。
     */
    List<UserActionMemoSettingsEntity> findByReminderEnabledTrueAndReminderTimeIsNotNull();

    // ==================================================================
    // クロスドメインFK撤廃キャンペーン 第二陣D（退会30日後の物理削除時削除）
    // ==================================================================

    /**
     * 指定ユーザーの行動メモ設定（user_action_memo_settings・PK=user_id の 1:1 行）を物理削除する。
     *
     * <p>{@code ActionMemoAnonymizationEventListener#onAccountPurged} が退会30日後
     * （{@code AccountPurgedEvent} 物理削除完了）に呼び出し、1:1 設定（復元価値があるため
     * 30日撤回ウィンドウ保持後に削除）を先行削除するための安全弁メソッド。
     * これにより V99.001 で撤廃する {@code fk_user_action_memo_settings_user}
     * （ON DELETE CASCADE・user_id は PK 兼 FK）が冗長になる。</p>
     *
     * <p>本 Entity は論理削除を持たない（{@code @SQLRestriction} なし）ため JPQL で削除する。
     * PK=user_id のため最大1行のみ削除される。</p>
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数（0 または 1）
     */
    @Modifying
    @Query("DELETE FROM UserActionMemoSettingsEntity s WHERE s.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
