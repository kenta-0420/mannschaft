package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.common.security.IntentionallyPublic;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: クラスレベルに {@link IntentionallyPublic} 監査済マーカーを付与した Controller。
 *
 * <p>Mapping メソッド自体は無印だが、<b>宣言クラスへのマーカー付与により認可シグナルあり
 * （合格）</b>と判定されるべきケース（{@code getOwner().isAnnotatedWith} 経路の担保）。
 * {@code @PreAuthorize} のクラスレベル付与と完全対称にクラスレベルのマーカーも
 * 監査済みとして扱われることを保証する。
 */
@IntentionallyPublic
@RestController
@RequestMapping("/fixtures/intentionally-public-marker-class-annotated")
public class IntentionallyPublicMarkerClassAnnotatedController {

    private final DummyPlainService plainService = new DummyPlainService();

    /** 無印だがクラスレベルの監査済マーカーで合格すべき公開エンドポイント。 */
    @GetMapping
    public String plainMethod(Long id) {
        return plainService.loadData(id);
    }
}
