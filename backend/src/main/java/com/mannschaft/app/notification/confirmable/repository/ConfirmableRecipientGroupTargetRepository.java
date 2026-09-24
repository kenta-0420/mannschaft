package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupTargetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先グループのターゲットリポジトリ。
 */
public interface ConfirmableRecipientGroupTargetRepository
        extends JpaRepository<ConfirmableRecipientGroupTargetEntity, Long> {

    /**
     * グループIDに紐づくターゲット一覧を取得する。
     *
     * @param groupId グループID
     * @return ターゲット一覧
     */
    List<ConfirmableRecipientGroupTargetEntity> findByGroupId(UUID groupId);

    /**
     * グループの更新時、旧ターゲットを一括削除する（新ターゲットで置き換えるため）。
     */
    void deleteByGroupId(UUID groupId);
}
