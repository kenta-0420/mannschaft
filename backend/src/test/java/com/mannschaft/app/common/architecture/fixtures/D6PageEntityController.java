package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: {@code Page<Entity>} で Entity を返す公開エンドポイント
 * （D-6 で違反として検出されるべき — Spring Data の Page ジェネリクスの Entity 捕捉担保）。
 */
@RestController
@RequestMapping("/fixtures/d6/page-entity")
public class D6PageEntityController {

    /** Page&lt;Entity&gt; 返し（違反）。 */
    @GetMapping
    public Page<DummyD6ExposedEntity> paged() {
        return null;
    }
}
