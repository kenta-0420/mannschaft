package com.mannschaft.app.role.dto;

/** 招待の所属先（TEAM / ORGANIZATION とその ID）。招待行はロックせず読んだ射影。 */
public record InviteScopeRef(String scopeType, Long scopeId) {
}
