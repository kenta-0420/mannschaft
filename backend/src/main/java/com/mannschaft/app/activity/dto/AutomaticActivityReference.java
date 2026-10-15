package com.mannschaft.app.activity.dto;

import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.activity.ActivityVisibility;

/** source ACLのbulk判定に必要な参照だけ。未公開actual値を取得しない。 */
public record AutomaticActivityReference(Long id, String scopeType, Long scopeId, Long scheduleId,
                                         Long createdBy, ActivityVisibility visibility, ActivityStatus status) {}
