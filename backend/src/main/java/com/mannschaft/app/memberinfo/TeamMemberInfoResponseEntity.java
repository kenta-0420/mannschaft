package com.mannschaft.app.memberinfo;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "team_member_info_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamMemberInfoResponseEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long fieldId;

    @Column(nullable = true, columnDefinition = "TEXT")
    private String valuePlain;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = true, columnDefinition = "TEXT")
    private String valueEncrypted;

    @Column(nullable = true)
    private Integer encryptionKeyVersion;

    @Column(nullable = true)
    private LocalDateTime confirmedAt;

    @Column(nullable = true)
    private LocalDateTime lastReminderSentAt;
}
