package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * ユーザー村ニックネームリポジトリ（F17.1 Phase 1）。
 *
 * <p>Phase 1 は {@code villageId IS NULL} の行のみ運用（1 ユーザー = 1 ニックネーム）。
 * Phase 2 で村ごと上書き行を追加可能にする。</p>
 */
public interface UserVillageNicknameRepository extends JpaRepository<UserVillageNicknameEntity, UUID> {

    /** Phase 1: ユーザーの全村共通ニックネーム（villageId IS NULL）を取得。 */
    Optional<UserVillageNicknameEntity> findByUserIdAndVillageIdIsNull(Long userId);

    /** Phase 2: ユーザーの特定村上書きニックネームを取得。 */
    Optional<UserVillageNicknameEntity> findByUserIdAndVillageId(Long userId, UUID villageId);

    /** ニックネームのプラットフォーム全体ユニーク制約（先着優先）の検証用。 */
    boolean existsByNickname(String nickname);
}
