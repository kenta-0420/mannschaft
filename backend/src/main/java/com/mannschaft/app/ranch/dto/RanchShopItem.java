package com.mannschaft.app.ranch.dto;

/** 承認済みSKUの現行価格と本人の所有状態。 */
public record RanchShopItem(String skuKey, String collectibleKey, String labelKey,
                            String assetKey, String pricePoints, String priceVersion,
                            boolean isOwned) { }
