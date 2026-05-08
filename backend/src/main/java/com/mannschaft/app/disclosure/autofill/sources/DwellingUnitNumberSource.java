package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code dwelling_unit.unit_number} — 部屋番号。
 *
 * <p>設計書 F09.14 §5.2 表中「部屋番号」に対応。</p>
 */
@Component
public class DwellingUnitNumberSource extends AbstractDwellingUnitSource {

    public DwellingUnitNumberSource(DwellingUnitRepository dwellingUnitRepository) {
        super(dwellingUnitRepository);
    }

    @Override
    public String key() {
        return "dwelling_unit.unit_number";
    }

    @Override
    protected Object extract(DwellingUnitEntity entity) {
        return entity.getUnitNumber();
    }
}
