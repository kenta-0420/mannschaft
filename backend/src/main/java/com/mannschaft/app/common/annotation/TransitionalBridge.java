package com.mannschaft.app.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 業務返却中の旧FQCNを維持する互換橋渡しを示す移行用の印。
 *
 * <p>付与された型はkernelに含めず、業務の公開窓口への委譲だけを残す。
 * 理由と撤去予定レーンを付与先ごとに明記し、派生クラスには印を引き継がない。</p>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TransitionalBridge {

    /**
     * 互換橋渡しを残す理由。
     *
     * @return 橋渡しが必要な具体的理由
     */
    String reason();

    /**
     * 互換橋渡しを撤去する予定のレーン。
     *
     * @return 設計で定めた撤去予定レーン
     */
    String removalLane();
}
