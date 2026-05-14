package com.mannschaft.app.residencestatus.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 居住者アクティビティスナップショット更新イベント（F09.16 S3-B）。
 *
 * <p>日次集計バッチが特定居住者のスナップショットを UPSERT したときに発火する。
 * 将来的にダッシュボード集計キャッシュの即時無効化などに利用する。
 */
@Getter
public class ResidentActivityUpdatedEvent extends ApplicationEvent {

    private final Long organizationId;
    private final Long residentRegistryId;
    private final int newActivityScore;

    public ResidentActivityUpdatedEvent(Object source, Long organizationId,
                                        Long residentRegistryId, int newActivityScore) {
        super(source);
        this.organizationId = organizationId;
        this.residentRegistryId = residentRegistryId;
        this.newActivityScore = newActivityScore;
    }
}
