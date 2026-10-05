package com.mannschaft.app.timeline.service;
import com.mannschaft.app.timeline.dto.TimelineRanchRewardPayload;
import com.mannschaft.app.timeline.dto.TimelineContentFingerprint;
/** 本文を含まず、ACTIVE保護下の通常新規投稿だけから凍結する私有捕捉。 */
record TimelineRanchCapture(TimelineRanchRewardPayload payload, TimelineContentFingerprint fingerprint) { }
