package com.mannschaft.app.pointcard.repository;

import com.mannschaft.app.common.repository.AbstractUserOwnedRepository;
import com.mannschaft.app.pointcard.entity.PointCardGroupEntity;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * ポイントカードグループのリポジトリ。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §5.3
 *
 * <p>個人スコープのため {@link AbstractUserOwnedRepository} を継承する。
 * 一覧取得は「display_order 昇順 → created_at 昇順」で安定ソートする。
 */
@Repository
public interface PointCardGroupRepository
        extends AbstractUserOwnedRepository<PointCardGroupEntity, UUID> {

    /**
     * 指定ユーザーのグループ一覧を表示順で取得する。
     *
     * <p>並び順: {@code display_order ASC, created_at ASC}。
     * インデックス {@code idx_pcg_user (user_id, display_order)} を活用する。
     */
    List<PointCardGroupEntity> findAllByUserIdOrderByDisplayOrderAscCreatedAtAsc(Long userId);
}
