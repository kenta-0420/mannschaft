package com.mannschaft.app.disclosure.autofill.sources;

import com.mannschaft.app.disclosure.autofill.AutoFillContext;
import com.mannschaft.app.disclosure.autofill.AutoFillSource;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自動引用ソース: {@code property_history.packages} — 工事履歴一覧。
 *
 * <p>設計書 F09.14 §5.2 表中「工事履歴一覧」に対応。AUTO_TABLE 用の
 * {@code List<Map<String,Object>>} を返却する。</p>
 *
 * <p><b>filter 仕様（設計書 §5.2 例より）</b>:</p>
 * <ul>
 *   <li>{@code isDisclosable}: Boolean — true の場合 isDisclosable=true のみ抽出（既定: true）</li>
 *   <li>{@code status}: String / List&lt;String&gt; — WorkPackageStatus 名でフィルタ</li>
 *   <li>{@code workType}: String / List&lt;String&gt; — WorkType 名でフィルタ（事故告知欄等）</li>
 * </ul>
 *
 * <p>未知の filter キーは無視する（設計書 §6.4 ホワイトリスト方式の精神に従い、
 * 黙って無視する方が安全）。 enum 名が不正な場合は WARN ログを出して該当値を捨てる。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PropertyHistoryPackagesSource implements AutoFillSource {

    /** 設計書 §5.2「工事履歴」: 範囲制限を 30 年に固定（実用上十分かつ DB 走査量の安全弁）。 */
    private static final int LOOKBACK_YEARS = 30;

    private final PropertyWorkPackageRepository repository;

    @Override
    public String key() {
        return "property_history.packages";
    }

    @Override
    public Object resolve(AutoFillContext context) {
        if (context.scopeType() == null || context.scopeId() == null) {
            return List.of();
        }

        // 既定では 30 年遡る。 findDisclosable は isDisclosable=true 固定の専用クエリのため、
        // 「isDisclosable=false も含めたい」ケースが将来出てきた場合は別クエリを用意する。
        LocalDate from = LocalDate.now().minusYears(LOOKBACK_YEARS);
        List<PropertyWorkPackageEntity> raw =
                repository.findDisclosable(context.scopeType(), context.scopeId(), from);

        Set<WorkPackageStatus> statusFilter = parseEnumSet(
                context.filter().get("status"), WorkPackageStatus.class);
        Set<WorkType> workTypeFilter = parseEnumSet(
                context.filter().get("workType"), WorkType.class);

        return raw.stream()
                .filter(p -> statusFilter.isEmpty() || statusFilter.contains(p.getStatus()))
                .filter(p -> workTypeFilter.isEmpty() || workTypeFilter.contains(p.getWorkType()))
                .map(this::toRow)
                .toList();
    }

    private Map<String, Object> toRow(PropertyWorkPackageEntity p) {
        // LinkedHashMap でテンプレート側の表示順を安定化させる
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("workType", p.getWorkType() != null ? p.getWorkType().name() : null);
        row.put("category", p.getCategory());
        row.put("title", p.getTitle());
        row.put("actualEndDate", p.getActualEndDate());
        row.put("vendorName", p.getVendorNameSnapshot());
        row.put("warrantyUntil", p.getWarrantyUntil());
        row.put("status", p.getStatus() != null ? p.getStatus().name() : null);
        return row;
    }

    /**
     * filter 値（String / Collection&lt;String&gt;）を Enum 集合に正規化する。
     *
     * <p>不正な enum 名は WARN ログを出して該当要素のみ捨てる。
     * {@code null} or 空コレクションの場合はフィルタ無効を意味する空 Set を返す。</p>
     */
    private <E extends Enum<E>> Set<E> parseEnumSet(Object raw, Class<E> enumType) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof Collection<?> col) {
            return col.stream()
                    .map(Object::toString)
                    .map(name -> safeValueOf(name, enumType))
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        E single = safeValueOf(raw.toString(), enumType);
        return single == null ? Set.of() : Set.of(single);
    }

    private <E extends Enum<E>> E safeValueOf(String name, Class<E> enumType) {
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring unknown {} filter value: {}", enumType.getSimpleName(), name);
            return null;
        }
    }
}
