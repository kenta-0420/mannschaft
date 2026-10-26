package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import com.mannschaft.app.parking.repository.ParkingSpaceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 自動引用ソース: {@code parking.spaces_total} — 駐車場区画数（総数）。
 *
 * <p>設計書 F09.14 §5.2 表中「駐車場区画数」に対応。
 * {@link ParkingSpaceRepository#countByScopeTypeAndScopeId(String, Long)} を利用する。</p>
 */
@Component
@RequiredArgsConstructor
public class ParkingSpacesTotalSource implements AutoFillSource {

    private final ParkingSpaceRepository parkingSpaceRepository;

    @Override
    public String key() {
        return "parking.spaces_total";
    }

    @Override
    public Object resolve(AutoFillContext context) {
        if (context.scopeType() == null || context.scopeId() == null) {
            return 0L;
        }
        return parkingSpaceRepository.countByScopeTypeAndScopeId(
                context.scopeType(), context.scopeId());
    }
}
