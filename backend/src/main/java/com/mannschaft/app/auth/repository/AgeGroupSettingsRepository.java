package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.AgeGroupSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * F01.9 年齢確認・保護者同意機能: 年齢グループ設定マスタリポジトリ。
 *
 * <p>age_group_settings テーブルへのアクセスを提供する。
 * PK は自然キー（age_group 文字列）のため、ID 型は {@link String}。</p>
 *
 * <p>マスタテーブルのため基本的な CRUD（findAll / findById）は
 * {@link JpaRepository} から継承して使用する。
 * 追加の検索メソッドは現時点では不要。</p>
 */
public interface AgeGroupSettingsRepository extends JpaRepository<AgeGroupSettingsEntity, String> {
    // JpaRepository が提供する以下のメソッドで充足:
    //   findAll()             — 全年齢グループ設定を取得
    //   findById(ageGroup)    — 特定の年齢グループ設定を取得
    //   save(entity)          — 設定の作成・更新（運用バッチ用）
}
