package com.mannschaft.app.activity;

import com.mannschaft.app.activity.entity.ActivityResultEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 活動予定同期の永続契約を実装前に固定する試練。 */
class ActivityScheduleSyncTrialTest {

    @Test
    void 予定由来基準と跨日と競合versionを永続化できる() {
        assertThat(ActivityResultEntity.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .contains("scheduleSyncState", "activityEndDate", "version");
    }
}
