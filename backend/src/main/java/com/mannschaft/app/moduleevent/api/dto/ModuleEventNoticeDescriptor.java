package com.mannschaft.app.moduleevent.api.dto;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * publication 削除後も通知予約が保持する、不変の通知識別情報。
 *
 * <p>noticeKey は resolverKey / operation / episode / phase / audience / scope から決定し、短期 retry で増やさない。
 * noticeRef は既存通知の Long sourceId に渡す正規の一意参照で、UUID の切詰めや hash 変換ではない。
 * 通知文面・診断・秘密値・保留 room の ID や直リンクを持たない。</p>
 */
@Getter
@RequiredArgsConstructor
public final class ModuleEventNoticeDescriptor {
    @NonNull
    private final UUID noticeKey;
    @NonNull
    private final Long noticeRef;
    @NonNull
    private final ModuleEventImpactBinding impact;
    @NonNull
    private final ModuleEventNoticePhase phase;
    @NonNull
    private final ModuleEventNoticeAudience audience;
}
