package com.mannschaft.app.role.dto;

/** 個人招待の判定に必要な、招待先自身のcommit済み閉鎖状態。Entityはdomain外へ返さない。 */
public record InviteScopeState(boolean archived, boolean deleted, boolean provisioned, long closureGeneration) {
}
