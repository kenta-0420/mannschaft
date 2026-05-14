package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.pointcard.dto.PointCardProviderResponse;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ポイントカードプロバイダー（運営マスタ）サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.2
 *
 * <p>カード追加フォームのプリセットボタン用にロゴ・ブランドカラーを含む
 * プロバイダー一覧を返却する。{@code is_active=false} のレコードは除外し、
 * カテゴリ昇順・表示名昇順で安定ソートする。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointCardProviderService {

    private final PointCardProviderRepository providerRepository;

    /**
     * 有効化されている全プロバイダーを一覧で返す。
     *
     * @return is_active=true のプロバイダーを category, display_name 昇順で並べた一覧
     */
    public List<PointCardProviderResponse> listActiveProviders() {
        return providerRepository
                .findAllByActiveTrueOrderByCategoryAscDisplayNameAsc()
                .stream()
                .map(PointCardProviderResponse::from)
                .toList();
    }
}
