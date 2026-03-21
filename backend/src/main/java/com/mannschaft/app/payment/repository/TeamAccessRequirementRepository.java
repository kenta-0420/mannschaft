package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * チーム全体ロック用支払い要件リポジトリ。
 */
public interface TeamAccessRequirementRepository extends JpaRepository<TeamAccessRequirementEntity, Long> {

    /**
     * チーム ID で支払い要件一覧を取得する。
     */
    List<TeamAccessRequirementEntity> findByTeamId(Long teamId);

    /**
     * チーム ID で全件削除する（一括設定の置換用）。
     */
    void deleteByTeamId(Long teamId);

    /**
     * 支払い項目 ID で全件削除する（支払い項目論理削除時のクリーンアップ用）。
     */
    void deleteByPaymentItemId(Long paymentItemId);
}
