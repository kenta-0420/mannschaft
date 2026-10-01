package com.mannschaft.app.member.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * メンバー統合画面サブタブ可視性設定が更新された（差分があった）ことを表すイベント（F06.6）。
 *
 * <p>{@link com.mannschaft.app.member.service.MemberSubtabVisibilityWriter#applyUpdates} が書き込み TX の中で
 * 発行し、{@link com.mannschaft.app.auth.event.AuditLogEventListener} が AFTER_COMMIT で購読して監査ログを
 * 記録する。</p>
 *
 * <p>member ドメインの TX の中から auth ドメインの {@code AuditLogService} を直接呼ぶ設計は
 * ドメイン境界原則5（{@code @Transactional} はドメイン内に閉じる）に違反するため、イベント駆動で分離した
 * （PR #3387 D-3T 根治。circulation の {@code CirculationExportRequestedEvent} と同じ型）。</p>
 *
 * <p>{@code metadataJson} は member 側で組み立て済みの文字列であり、リスナーは加工せずに記録する。</p>
 */
@Getter
public class MemberSubtabVisibilityUpdatedEvent extends BaseEvent {

    /** 監査ログのイベント種別。 */
    public static final String AUDIT_EVENT_TYPE = "MEMBER_SUBTAB_VISIBILITY_UPDATED";

    private final Long actorUserId;
    /** TEAM スコープの時だけ値を持つ。 */
    private final Long teamId;
    /** ORGANIZATION スコープの時だけ値を持つ。 */
    private final Long organizationId;
    private final String metadataJson;

    public MemberSubtabVisibilityUpdatedEvent(Long actorUserId, Long teamId, Long organizationId,
                                              String metadataJson) {
        super();
        this.actorUserId = actorUserId;
        this.teamId = teamId;
        this.organizationId = organizationId;
        this.metadataJson = metadataJson;
    }
}
