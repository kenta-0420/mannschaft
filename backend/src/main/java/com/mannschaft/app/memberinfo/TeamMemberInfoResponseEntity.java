package com.mannschaft.app.memberinfo;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.common.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

@Entity
@Table(name = "team_member_info_responses")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
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

    /**
     * 回答内容を更新する（機密フィールドの場合は暗号化値、非機密の場合は平文）。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     * （toBuilder().build() は継承フィールド id を引き継がず INSERT 化するため使用しない）
     */
    public void applyUpsert(String valuePlain, String valueEncrypted, Integer encryptionKeyVersion,
                            LocalDateTime confirmedAt) {
        this.valuePlain = valuePlain;
        this.valueEncrypted = valueEncrypted;
        this.encryptionKeyVersion = encryptionKeyVersion;
        this.confirmedAt = confirmedAt;
    }

    /**
     * リマインド送信日時を更新する。
     * managed エンティティを直接ミューテートして id を保持したまま UPDATE を発行する。
     */
    public void updateLastReminderSentAt(LocalDateTime lastReminderSentAt) {
        this.lastReminderSentAt = lastReminderSentAt;
    }
}
