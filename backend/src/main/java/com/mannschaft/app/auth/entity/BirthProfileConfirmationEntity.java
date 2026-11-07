package com.mannschaft.app.auth.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import java.time.Instant;
import java.util.UUID;

/** 本人プロフィール版・用途・期限の署名証跡。原氏名・カナ・日付は複製しない。 */
@Entity
@Table(name = "birth_profile_confirmations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class BirthProfileConfirmationEntity extends UuidV7Entity {

    @Column(nullable = false, updatable = false) private Long userId;
    @Column(nullable = false, updatable = false) private long profileRevision;
    private UUID withdrawalAttemptId;
    @Column(nullable = false, updatable = false, length = 64) private String fingerprint;
    @Column(nullable = false, updatable = false, length = 64) private String signature;
    @Column(nullable = false, updatable = false, length = 64) private String keyId;
    @Column(nullable = false, updatable = false, length = 80) private String purpose;
    @Column(nullable = false, updatable = false) private Instant expiresAt;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false, updatable = false) private Instant updatedAt;
}
