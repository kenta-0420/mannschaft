package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.entity.SystemFormPresetEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * システムフォームプリセットリポジトリ。
 */
public interface SystemFormPresetRepository extends JpaRepository<SystemFormPresetEntity, Long> {

    /**
     * 有効なプリセット一覧を取得する。
     */
    List<SystemFormPresetEntity> findByIsActiveTrueOrderByNameAsc();

    /**
     * カテゴリ指定で有効なプリセット一覧を取得する。
     */
    List<SystemFormPresetEntity> findByCategoryAndIsActiveTrueOrderByNameAsc(String category);
}
