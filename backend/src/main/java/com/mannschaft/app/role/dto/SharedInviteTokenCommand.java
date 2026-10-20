package com.mannschaft.app.role.dto;

import java.time.Instant;

/** 非TX facadeが検証/計算した共有発行command。HTTP入力へ束縛せず、世代は親portの値だけを使う。 */
public record SharedInviteTokenCommand(String scopeType, Long scopeId, Long roleId, String roleName,
                                      Long createdBy, Instant expiresAt, Integer maxUses,
                                      long scopeClosureGeneration) {
}
