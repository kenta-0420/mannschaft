package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.entity.UserMuteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ユーザーミュートリポジトリ。
 */
public interface UserMuteRepository extends JpaRepository<UserMuteEntity, Long> {

    /**
     * ユーザーのミュート一覧を取得する。
     */
    List<UserMuteEntity> findByUserId(Long userId);

    /**
     * ユーザー・ミュート種別・ミュート対象IDでミュートを取得する。
     */
    Optional<UserMuteEntity> findByUserIdAndMutedTypeAndMutedId(Long userId, String mutedType, Long mutedId);

    /**
     * ユーザーがミュート済みかを判定する。
     */
    boolean existsByUserIdAndMutedTypeAndMutedId(Long userId, String mutedType, Long mutedId);
}
