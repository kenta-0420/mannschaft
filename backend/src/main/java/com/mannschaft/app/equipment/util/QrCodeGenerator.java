package com.mannschaft.app.equipment.util;

import com.mannschaft.app.equipment.EquipmentScopeType;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * QRコード識別子を生成するユーティリティ。
 * <p>
 * 生成ルール: {@code EQ-{scopePrefix}{scopeId}-{UUID v7 先頭8桁}}
 * <p>例: {@code EQ-T10-a1b2c3d4}（チームID=10）、{@code EQ-O5-e5f6g7h8}（組織ID=5）</p>
 */
@Component
public class QrCodeGenerator {

    /**
     * QRコード識別子を生成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return QRコード識別子（例: EQ-T10-a1b2c3d4）
     */
    public String generate(EquipmentScopeType scopeType, Long scopeId) {
        String prefix = scopeType == EquipmentScopeType.TEAM ? "T" : "O";
        String uuidFragment = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return String.format("EQ-%s%d-%s", prefix, scopeId, uuidFragment);
    }

    /**
     * QRコードからディープリンクURLを生成する。
     *
     * @param qrCode     QRコード識別子
     * @param domainBase ドメインベースURL（例: https://app.mannschaft.example）
     * @return ディープリンクURL
     */
    public String toDeepLinkUrl(String qrCode, String domainBase) {
        return domainBase + "/eq/" + qrCode;
    }
}
