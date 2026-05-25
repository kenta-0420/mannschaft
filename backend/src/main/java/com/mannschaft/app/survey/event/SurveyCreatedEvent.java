package com.mannschaft.app.survey.event;

import com.mannschaft.app.common.event.BaseEvent;
import lombok.Getter;

/**
 * アンケート作成イベント。
 *
 * <p>アンケート作成後にトランザクションコミットが完了した時点で発行される。
 * 掲示板スレッド自動生成等の非同期連携に使用する。</p>
 */
@Getter
public class SurveyCreatedEvent extends BaseEvent {

    /** アンケートID。 */
    private final long surveyId;

    /**
     * スコープ種別（ORGANIZATION / TEAM / COMMITTEE 等）。
     * SurveyEntity の scopeType カラムに相当する文字列。
     */
    private final String scopeType;

    /** スコープID。 */
    private final long scopeId;

    /** アンケートのタイトル。 */
    private final String title;

    public SurveyCreatedEvent(long surveyId, String scopeType, long scopeId, String title) {
        super();
        this.surveyId = surveyId;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.title = title;
    }
}
