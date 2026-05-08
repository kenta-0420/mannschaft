package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code dwelling_unit.layout} — 間取り。
 *
 * <p>設計書 F09.14 §5.2 表中「間取り」に対応。</p>
 */
@Component
public class DwellingUnitLayoutSource extends AbstractDwellingUnitSource {

    public DwellingUnitLayoutSource(DwellingUnitRepository dwellingUnitRepository) {
        super(dwellingUnitRepository);
    }

    @Override
    public String key() {
        return "dwelling_unit.layout";
    }

    @Override
    protected Object extract(DwellingUnitEntity entity) {
        return entity.getLayout();
    }
}
