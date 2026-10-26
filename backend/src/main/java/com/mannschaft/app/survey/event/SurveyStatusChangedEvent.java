package com.mannschaft.app.survey.event;

import com.mannschaft.app.common.event.BaseEvent;
import com.mannschaft.app.survey.SurveyStatus;
import lombok.Getter;

/**
 * アンケートステータス変更イベント。
 *
 * <p>アンケートのステータスが変わった時点で発行される。
 * 例: PUBLISHED → CLOSED 時に掲示板スレッドを自動ロックする。</p>
 */
@Getter
public class SurveyStatusChangedEvent extends BaseEvent {

    /** アンケートID。 */
    private final long surveyId;

    /** 変更後のステータス。 */
    private final SurveyStatus newStatus;

    public SurveyStatusChangedEvent(long surveyId, SurveyStatus newStatus) {
        super();
        this.surveyId = surveyId;
        this.newStatus = newStatus;
    }
}
