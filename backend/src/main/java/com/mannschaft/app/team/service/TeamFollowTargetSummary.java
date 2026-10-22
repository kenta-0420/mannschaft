package com.mannschaft.app.team.service;

/** 閲覧者に依存せず公開できるフォロー先チームの最小値。 */
public record TeamFollowTargetSummary(Long id, String name, String slug) {
}
