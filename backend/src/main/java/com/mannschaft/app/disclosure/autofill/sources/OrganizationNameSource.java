package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code organization.name} — 物件名（組織名）。
 *
 * <p>設計書 F09.14 §5.2 表中「物件名」に対応。</p>
 */
@Component
@RequiredArgsConstructor
public class OrganizationNameSource implements AutoFillSource {

    private final OrganizationRepository organizationRepository;

    @Override
    public String key() {
        return "organization.name";
    }

    @Override
    public Object resolve(AutoFillContext context) {
        if (context.scopeId() == null) {
            return null;
        }
        return organizationRepository.findById(context.scopeId())
                .map(OrganizationEntity::getName)
                .orElse(null);
    }
}
