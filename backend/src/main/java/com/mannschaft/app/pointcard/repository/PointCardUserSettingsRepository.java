package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.pointcard.entity.PointCardUserSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ポイントカードウォレットのユーザー設定リポジトリ。
 *
 * <p>PK = user_id（Long）の単純な 1:1 設定テーブル。
 * 個人スコープテーブルだが AbstractUserOwnedRepository は本テーブルの
 * 「1 ユーザー 1 レコード」性質と噛み合わないため JpaRepository を直接利用する
 * （findByUserId を Optional で返す API のほうが取り回しやすい）。
 */
@Repository
public interface PointCardUserSettingsRepository
        extends JpaRepository<PointCardUserSettingsEntity, Long> {

    /**
     * 指定ユーザーの設定を取得する。
     */
    Optional<PointCardUserSettingsEntity> findByUserId(Long userId);

    /**
     * 指定ユーザーの設定を削除する（退会30日後の物理削除時の安全弁）。
     *
     * <p>クロスドメインFK撤廃キャンペーン 第二陣C。{@code fk_pcus_user}（user CASCADE）撤廃に伴い、
     * {@code PointCardAnonymizationEventListener#onAccountPurged} が退会30日後に 1:1 設定（個人設定）を
     * 先行削除するために使用する。user_id は PK 兼 FK のため最大1行のみ削除される。</p>
     */
    void deleteByUserId(Long userId);
}
