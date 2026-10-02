package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.dto.PriceRevisionListQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link BillingPriceVersionRepositoryCustom} の実装（Spring Data フラグメント規約:
 * {@code <RepositoryName>Impl}）。
 *
 * <p>N+1 回避のため、この検索はband明細を一切取得しない（要約のみ・AC-65）。
 * ソート・ページングは常に {@link PriceRevisionListQuery} が固定した
 * {@code effectiveFrom DESC, id DESC}（AC-62・AC-62a）。</p>
 */
@Repository
class BillingPriceVersionRepositoryImpl implements BillingPriceVersionRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public List<BillingPriceVersionEntity> searchSummaries(PriceRevisionListQuery query) {
        StringBuilder jpql = new StringBuilder(
                "SELECT p FROM BillingPriceVersionEntity p WHERE p.deletedAt IS NULL");
        List<Object[]> params = new ArrayList<>();
        if (query.productKind() != null) {
            jpql.append(" AND p.productKind = :productKind");
            params.add(new Object[] {"productKind", query.productKind()});
        }
        if (query.productKey() != null) {
            jpql.append(" AND p.productKey = :productKey");
            params.add(new Object[] {"productKey", query.productKey()});
        }
        if (query.scopeKind() != null) {
            jpql.append(" AND p.scopeKind = :scopeKind");
            params.add(new Object[] {"scopeKind", query.scopeKind()});
        }
        if (query.status() != null) {
            jpql.append(" AND p.status = :status");
            params.add(new Object[] {"status", BillingPriceVersionStatus.valueOf(query.status())});
        }
        jpql.append(" ORDER BY p.effectiveFrom DESC, p.id DESC");

        TypedQuery<BillingPriceVersionEntity> typedQuery =
                entityManager.createQuery(jpql.toString(), BillingPriceVersionEntity.class);
        for (Object[] param : params) {
            typedQuery.setParameter((String) param[0], param[1]);
        }

        Pageable pageable = query.pageRequest();
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());
        return typedQuery.getResultList();
    }
}
