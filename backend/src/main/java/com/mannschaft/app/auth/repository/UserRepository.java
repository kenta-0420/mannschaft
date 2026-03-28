package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * ユーザーリポジトリ。
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    @org.springframework.data.jpa.repository.Query("SELECT u FROM UserEntity u WHERE u.displayName LIKE %:keyword% OR u.email LIKE %:keyword%")
    java.util.List<UserEntity> searchByKeyword(@org.springframework.data.repository.query.Param("keyword") String keyword, org.springframework.data.domain.Pageable pageable);

    long countByLastLoginAtAfterAndStatusAndDeletedAtIsNull(LocalDateTime since, UserEntity.UserStatus status);
}
