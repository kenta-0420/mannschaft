package com.mannschaft.app.safetycheck.repository;

import com.mannschaft.app.safetycheck.entity.SafetyCheckMessagePresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 安否確認メッセージプリセットリポジトリ。
 */
public interface SafetyCheckMessagePresetRepository extends JpaRepository<SafetyCheckMessagePresetEntity, Long> {

    /**
     * 有効なプリセットを表示順で取得する。
     */
    List<SafetyCheckMessagePresetEntity> findByIsActiveTrueOrderBySortOrderAsc();

    /**
     * 全プリセットを表示順で取得する（管理者用）。
     */
    List<SafetyCheckMessagePresetEntity> findAllByOrderBySortOrderAsc();
}
