package com.mannschaft.app.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * WebAuthn資格情報リポジトリ。
 */
public interface WebAuthnCredentialRepository extends JpaRepository<WebAuthnCredentialEntity, Long> {

    Optional<WebAuthnCredentialEntity> findByCredentialId(String credentialId);

    List<WebAuthnCredentialEntity> findByUserId(Long userId);
}
