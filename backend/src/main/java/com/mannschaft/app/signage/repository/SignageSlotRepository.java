package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * デジタルサイネージ スロットリポジトリ。
 */
public interface SignageSlotRepository extends JpaRepository<SignageSlotEntity, Long> {

    /**
     * 画面IDに紐づくアクティブなスロット一覧を表示順で取得する。
     */
    List<SignageSlotEntity> findByScreenIdAndIsActiveTrueOrderBySlotOrderAsc(Long screenId);

    /**
     * 画面IDに紐づく全スロット一覧を表示順で取得する。
     */
    List<SignageSlotEntity> findByScreenIdOrderBySlotOrderAsc(Long screenId);

    /**
     * 画面IDに紐づくスロットの最大表示順を取得する。
     */
    @Query("SELECT MAX(s.slotOrder) FROM SignageSlotEntity s WHERE s.screenId = :screenId")
    Optional<Integer> findMaxSlotOrderByScreenId(@Param("screenId") Long screenId);
}
