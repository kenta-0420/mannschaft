package com.mannschaft.app.corkboard.repository;

import com.mannschaft.app.corkboard.entity.CorkboardCardGroupEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * コルクボードカード⇔セクション中間テーブルリポジトリ。
 */
public interface CorkboardCardGroupRepository extends JpaRepository<CorkboardCardGroupEntity, Long> {

    /**
     * セクション内のカード紐付け一覧を取得する。
     */
    List<CorkboardCardGroupEntity> findByGroupId(Long groupId);

    /**
     * カードIDとセクションIDで紐付けを取得する。
     */
    Optional<CorkboardCardGroupEntity> findByCardIdAndGroupId(Long cardId, Long groupId);

    /**
     * カードIDで全紐付けを削除する。
     */
    void deleteByCardId(Long cardId);

    /**
     * セクションIDで全紐付けを削除する。
     */
    void deleteByGroupId(Long groupId);
}
