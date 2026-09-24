package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループリポジトリ。
 */
public interface ConfirmableRecipientGroupRepository
        extends JpaRepository<ConfirmableRecipientGroupEntity, UUID> {

    /**
     * スコープ配下の有効なグループ一覧を取得する（論理削除除外）。
     */
    List<ConfirmableRecipientGroupEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId);

    /**
     * IDで有効なグループを取得する（論理削除除外。AC-15 の404秘匿に使用）。
     */
    Optional<ConfirmableRecipientGroupEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 同じスコープ・同名の有効なグループが既に存在するかを判定する（AC-31 の409重複検証）。
     */
    boolean existsByScopeTypeAndScopeIdAndNameAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId, String name);
}
