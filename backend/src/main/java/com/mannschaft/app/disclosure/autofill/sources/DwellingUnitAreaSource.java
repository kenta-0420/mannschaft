package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code dwelling_unit.area} — 専有面積（㎡）。
 *
 * <p>設計書 F09.14 §5.2 表中「専有面積」に対応。{@code BigDecimal} を返す。</p>
 */
@Component
public class DwellingUnitAreaSource extends AbstractDwellingUnitSource {

    public DwellingUnitAreaSource(DwellingUnitRepository dwellingUnitRepository) {
        super(dwellingUnitRepository);
    }

    @Override
    public String key() {
        return "dwelling_unit.area";
    }

    @Override
    protected Object extract(DwellingUnitEntity entity) {
        return entity.getAreaSqm();
    }
}
