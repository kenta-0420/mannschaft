package com.mannschaft.app.sync.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F11.1 オフライン同期: コンフリクトエンティティ。
 * オフラインキュー同期時にバージョン衝突が発生した場合の記録を保持する。
 * resolution が NULL の間は未解決。ユーザーが CLIENT_WIN / SERVER_WIN / MANUAL_MERGE / DISCARDED を選択して解決する。
 */
@Entity
@Table(name = "offline_sync_conflicts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class OfflineSyncConflictEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String resourceType;

    @Column(nullable = false)
    private Long resourceId;

    @Column(nullable = false, columnDefinition = "JSON")
    private String clientData;

    @Column(nullable = false, columnDefinition = "JSON")
    private String serverData;

    @Column(nullable = false)
    private Long clientVersion;

    @Column(nullable = false)
    private Long serverVersion;

    @Column(length = 20)
    private String resolution;

    private LocalDateTime resolvedAt;

    /**
     * コンフリクトを解決する。
     *
     * @param resolution 解決方法 (CLIENT_WIN / SERVER_WIN / MANUAL_MERGE / DISCARDED)
     */
    public void resolve(String resolution) {
        this.resolution = resolution;
        this.resolvedAt = LocalDateTime.now();
    }
}
