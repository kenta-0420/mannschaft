package com.mannschaft.app.residencestatus.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * 年次更新キャンペーンクローズイベント（F09.16）。
 *
 * <p>手動クローズ・締切バッチによる自動クローズの両方で発火する。
 * 集計処理・未回答者への通知などの後続処理に使用する。</p>
 */
@Getter
public class AnnualReviewClosedEvent extends ApplicationEvent {

    private final UUID annualReviewId;
    private final Long organizationId;
    private final int responseCount;
    private final int totalCount;

    public AnnualReviewClosedEvent(Object source, UUID annualReviewId, Long organizationId,
                                    int responseCount, int totalCount) {
        super(source);
        this.annualReviewId = annualReviewId;
        this.organizationId = organizationId;
        this.responseCount = responseCount;
        this.totalCount = totalCount;
    }
}
