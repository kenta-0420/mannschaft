package com.mannschaft.app.activity.service;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.schedule.dto.ScheduleActivitySource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** autoの予定表示だけを変更する純activity writer。本文・参加者・公開状態は触らない。 */
@Service
@RequiredArgsConstructor
public class ActivityPlanningService {
    private final ActivityResultRepository activities;
    private final EntityManager entityManager;

    /** 呼出元が予定lockを先に保持する。削除行・manual・別scopeは取得しない。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void setPlanned(ScheduleActivitySource source, boolean value) {
        if (!"TEAM".equals(source.scopeType()) && !"ORGANIZATION".equals(source.scopeType())) return;
        for (ActivityResultEntity activity : activities.lockAutomaticScheduleLinks(source.id(),
                ActivityScopeType.valueOf(source.scopeType()), source.scopeId())) {
            entityManager.refresh(activity, LockModeType.PESSIMISTIC_WRITE);
            if (activity.isPlanned() != value) activity.changePlanned(value);
        }
        // @Versionは変更行のflush/commit時だけ進む。INSERTも実績上書きも行わない。
    }
}
