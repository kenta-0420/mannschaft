package com.mannschaft.app.residencestatus.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * 年次更新キャンペーン起動イベント（F09.16）。
 *
 * <p>キャンペーン作成直後に発火し、通知送信などの後続処理に使用する。</p>
 */
@Getter
public class AnnualReviewStartedEvent extends ApplicationEvent {

    private final UUID annualReviewId;
    private final Long organizationId;
    private final int targetCount;

    public AnnualReviewStartedEvent(Object source, UUID annualReviewId, Long organizationId, int targetCount) {
        super(source);
        this.annualReviewId = annualReviewId;
        this.organizationId = organizationId;
        this.targetCount = targetCount;
    }
}
