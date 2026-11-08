package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.ScheduleRanchRewardPayload;

/** 本文を含まない、本体commit後の源受付候補。 */
record ScheduleRanchCapture(ScheduleRanchRewardPayload payload) { }
