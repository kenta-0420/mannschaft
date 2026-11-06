package com.mannschaft.app.role.dto;

import java.time.Instant;

/** DM発行facadeが検証した値。scope世代はHTTP入力ではなく親専属portのsnapshot。 */
public record MembershipInviteTokenCommand(String scopeType, Long scopeId, Long roleId,
                                           Long targetUserId, Long createdBy, Instant expiresAt,
                                           long scopeClosureGeneration) {
}
