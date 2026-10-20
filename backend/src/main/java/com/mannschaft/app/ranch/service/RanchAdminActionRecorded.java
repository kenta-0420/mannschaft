package com.mannschaft.app.ranch.service;

import java.time.Instant;
import java.util.UUID;

/** 管理操作の最小監査事実。本文・設定全体・姓名・学習回答は複製しない。 */
public record RanchAdminActionRecorded(Long actorUserId, UUID commandId, String action,
        String resourceId, String reasonCode, Instant occurredAt) { }