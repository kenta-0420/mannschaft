package com.mannschaft.app.family;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * チーム記念日エンティティ。誕生日・記念日のリマインダー登録を保持する。
 */
@Entity
@Table(name = "team_anniversaries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TeamAnniversaryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Boolean repeatAnnually;

    @Column(nullable = false)
    private Integer notifyDaysBefore;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.repeatAnnually == null) {
            this.repeatAnnually = true;
        }
        if (this.notifyDaysBefore == null) {
            this.notifyDaysBefore = 1;
        }
    }

    /**
     * 記念日情報を更新する。
     *
     * @param name             記念日名
     * @param date             日付
     * @param repeatAnnually   毎年繰り返すか
     * @param notifyDaysBefore 何日前に通知するか
     */
    public void update(String name, LocalDate date, Boolean repeatAnnually, Integer notifyDaysBefore) {
        this.name = name;
        this.date = date;
        this.repeatAnnually = repeatAnnually;
        this.notifyDaysBefore = notifyDaysBefore;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
