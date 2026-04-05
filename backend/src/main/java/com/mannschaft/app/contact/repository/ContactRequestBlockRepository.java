package com.mannschaft.app.contact.repository;

import com.mannschaft.app.contact.entity.ContactRequestBlockEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 連絡先申請事前拒否リポジトリ。
 */
public interface ContactRequestBlockRepository extends JpaRepository<ContactRequestBlockEntity, Long> {

    /** 自分の事前拒否リスト一覧 */
    List<ContactRequestBlockEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 拒否設定が存在するか確認 */
    boolean existsByUserIdAndBlockedId(Long userId, Long blockedId);

    /** 拒否設定を削除 */
    @Modifying
    @Transactional
    void deleteByUserIdAndBlockedId(Long userId, Long blockedId);

    /** blockedId 視点：自分への申請を事前拒否しているユーザーが存在するか */
    boolean existsByBlockedIdAndUserId(Long blockedId, Long userId);
}
