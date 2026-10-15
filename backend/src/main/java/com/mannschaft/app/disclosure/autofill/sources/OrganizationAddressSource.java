package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code organization.address} — 所在地（都道府県 + 市区町村を結合）。
 *
 * <p>設計書 F09.14 §5.2 表中「所在地」に対応。
 * 現状 organizations テーブルには番地・建物名のカラムが存在しないため、
 * {@code prefecture + city} を結合した文字列を返す。詳細住所が必要な場合は
 * 手動入力で補完される運用（設計書 §5.1 流れ参照）。</p>
 */
@Component
@RequiredArgsConstructor
public class OrganizationAddressSource implements AutoFillSource {

    private final OrganizationRepository organizationRepository;

    @Override
    public String key() {
        return "organization.address";
    }

    @Override
    public Object resolve(AutoFillContext context) {
        if (context.scopeId() == null) {
            return null;
        }
        return organizationRepository.findById(context.scopeId())
                .map(this::formatAddress)
                .orElse(null);
    }

    private String formatAddress(OrganizationEntity org) {
        String prefecture = org.getPrefecture();
        String city = org.getCity();
        if (prefecture == null && city == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (prefecture != null) {
            sb.append(prefecture);
        }
        if (city != null) {
            sb.append(city);
        }
        return sb.toString();
    }
}
