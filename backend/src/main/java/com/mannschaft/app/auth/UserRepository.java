package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * ユーザーリポジトリ。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);
}
