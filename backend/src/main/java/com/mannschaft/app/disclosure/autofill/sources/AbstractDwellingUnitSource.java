package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import com.mannschaft.app.resident.entity.DwellingUnitEntity;
import com.mannschaft.app.resident.repository.DwellingUnitRepository;
import lombok.RequiredArgsConstructor;

import java.util.Optional;

/**
 * 居室情報系の自動引用ソース共通基底。
 *
 * <p>{@link AutoFillContext#targetDwellingUnitId()} を引いて
 * {@link DwellingUnitEntity} を取得し、サブクラスがそこから 1 フィールドを取り出す。</p>
 */
@RequiredArgsConstructor
abstract class AbstractDwellingUnitSource implements AutoFillSource {

    private final DwellingUnitRepository dwellingUnitRepository;

    /**
     * Entity から取り出すフィールドの抽出ロジックをサブクラスで実装する。
     */
    protected abstract Object extract(DwellingUnitEntity entity);

    @Override
    public final Object resolve(AutoFillContext context) {
        Long unitId = context.targetDwellingUnitId();
        if (unitId == null) {
            return null;
        }
        Optional<DwellingUnitEntity> entity = dwellingUnitRepository.findById(unitId);
        return entity.map(this::extract).orElse(null);
    }
}
