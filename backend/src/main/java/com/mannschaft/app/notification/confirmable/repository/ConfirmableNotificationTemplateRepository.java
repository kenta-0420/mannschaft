package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * F04.9 確認通知テンプレートリポジトリ。
 */
public interface ConfirmableNotificationTemplateRepository
        extends JpaRepository<ConfirmableNotificationTemplateEntity, Long> {

    /**
     * スコープ配下の有効なテンプレート一覧を取得する（論理削除除外）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 有効なテンプレートリスト
     */
    List<ConfirmableNotificationTemplateEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId);

    /**
     * IDで有効なテンプレートを取得する（論理削除除外）。
     *
     * @param id テンプレートID
     * @return テンプレート（存在しないまたは削除済みの場合 empty）
     */
    Optional<ConfirmableNotificationTemplateEntity> findByIdAndDeletedAtIsNull(Long id);
}
