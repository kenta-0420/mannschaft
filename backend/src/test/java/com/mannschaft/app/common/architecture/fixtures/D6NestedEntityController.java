package com.mannschaft.app.common.architecture.fixtures;

import com.mannschaft.app.common.ApiResponse;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * fixture: 3 段入れ子 {@code ResponseEntity<ApiResponse<Page<Entity>>>} で Entity を返す
 * 公開エンドポイント（D-6 で違反として検出されるべき — 深いジェネリクス入れ子でも
 * {@code getAllInvolvedRawTypes()} で最深の Entity を捕捉できるかの担保）。
 */
@RestController
@RequestMapping("/fixtures/d6/nested-entity")
public class D6NestedEntityController {

    /** ResponseEntity&lt;ApiResponse&lt;Page&lt;Entity&gt;&gt;&gt; 返し（違反）。 */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<DummyD6ExposedEntity>>> deeplyNested() {
        return null;
    }
}
