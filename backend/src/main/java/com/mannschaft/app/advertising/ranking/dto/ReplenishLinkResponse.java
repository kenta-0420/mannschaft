package com.mannschaft.app.advertising.ranking.dto;

/**
 * 備品補充リンクレスポンスDTO。
 */
public record ReplenishLinkResponse(boolean hasReplenishLink, String replenishUrl, String provider) {}
