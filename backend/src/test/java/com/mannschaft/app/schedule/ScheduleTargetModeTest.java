package com.mannschaft.app.schedule;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduleTargetModeTest {
    @Test
    void supports_all_and_selected_members() {
        assertThat(ScheduleTargetMode.valueOf("ALL_MEMBERS")).isEqualTo(ScheduleTargetMode.ALL_MEMBERS);
        assertThat(ScheduleTargetMode.valueOf("SELECTED_MEMBERS")).isEqualTo(ScheduleTargetMode.SELECTED_MEMBERS);
    }
}
