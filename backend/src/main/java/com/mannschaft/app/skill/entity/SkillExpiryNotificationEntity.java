package com.mannschaft.app.skill.entity;

import com.mannschaft.app.skill.NotificationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 資格期限通知送信履歴エンティティ。
 */
@Entity
@Table(name = "skill_expiry_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SkillExpiryNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long memberSkillId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationType notificationType;

    @Column(nullable = false)
    private LocalDateTime sentAt;
}
