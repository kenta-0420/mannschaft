package com.mannschaft.app.moduleevent.api.dto;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * 未完了試行と通知予約に保持する、業務影響の最小 scalar 識別情報。
 *
 * <p>operationId は論理操作、episodeId は同じ操作内の障害区間を業務側が安定識別する。
 * 自由文・URL・PII・汎用 JSON を持たない。organizationId は単独 TEAM では null を許す。
 * scopeId は正数、ORGANIZATION は scopeId と organizationId が一致することを登録時に検証する。
 * TEAM の現在の所属先と tenant の一致は業務 resolver が再検証し、識別子だけから許可を推測しない。</p>
 */
@Getter
@RequiredArgsConstructor
public final class ModuleEventImpactBinding {
    @NonNull
    private final String resolverKey;
    @NonNull
    private final UUID operationId;
    @NonNull
    private final UUID episodeId;
    @NonNull
    private final ModuleEventNoticeScope scopeType;
    @NonNull
    private final Long scopeId;
    private final Long organizationId;
}
