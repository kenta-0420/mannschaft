package com.mannschaft.app.line.repository;

import com.mannschaft.app.line.entity.UserLineConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ユーザーLINE連携リポジトリ。
 */
public interface UserLineConnectionRepository extends JpaRepository<UserLineConnectionEntity, Long> {

    /**
     * ユーザーIDで連携情報を取得する。
     */
    Optional<UserLineConnectionEntity> findByUserId(Long userId);

    /**
     * LINEユーザーIDで連携情報を取得する。
     */
    Optional<UserLineConnectionEntity> findByLineUserId(String lineUserId);

    /**
     * ユーザーIDで連携が存在するか確認する。
     */
    boolean existsByUserId(Long userId);

    /**
     * LINEユーザーIDで連携が存在するか確認する。
     */
    boolean existsByLineUserId(String lineUserId);

    /**
     * ユーザーIDで連携情報を削除する。
     */
    void deleteByUserId(Long userId);
}
