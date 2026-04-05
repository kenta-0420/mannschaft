package com.mannschaft.app.bulletin.repository;

import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 掲示板既読ステータスリポジトリ。
 */
public interface BulletinReadStatusRepository extends JpaRepository<BulletinReadStatusEntity, Long> {

    /**
     * スレッドとユーザーの既読状態を取得する。
     */
    Optional<BulletinReadStatusEntity> findByThreadIdAndUserId(Long threadId, Long userId);

    /**
     * スレッドとユーザーの既読状態が存在するか確認する。
     */
    boolean existsByThreadIdAndUserId(Long threadId, Long userId);

    /**
     * スレッドの既読ユーザー一覧を取得する。
     */
    List<BulletinReadStatusEntity> findByThreadIdOrderByReadAtDesc(Long threadId);

    /**
     * スレッドの既読数を取得する。
     */
    long countByThreadId(Long threadId);
}
