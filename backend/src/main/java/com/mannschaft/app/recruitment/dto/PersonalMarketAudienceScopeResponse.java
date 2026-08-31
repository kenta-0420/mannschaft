package com.mannschaft.app.recruitment.dto;

/** 本人向け個人札一覧だけで返す、固定公開先スコープ。 */
public record PersonalMarketAudienceScopeResponse(String scopeType, Long scopeId) {
}
