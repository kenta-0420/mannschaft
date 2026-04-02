package com.mannschaft.app.gdpr;

import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F12.3 個人データカバレッジバリデーター。
 * アプリ起動時に @PersonalData アノテーションが付いたエンティティのカテゴリが
 * PersonalDataCollector に登録されているかを検証する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalDataCoverageValidator implements ApplicationRunner {

    private final PersonalDataCollector personalDataCollector;

    /** スキャン対象のベースパッケージ */
    private static final String BASE_PACKAGE = "com.mannschaft.app";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        List<String> registeredKeys = personalDataCollector.getCategoryKeys();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(PersonalData.class));

        var candidates = scanner.findCandidateComponents(BASE_PACKAGE);

        for (var candidate : candidates) {
            try {
                Class<?> clazz = Class.forName(candidate.getBeanClassName());
                PersonalData annotation = clazz.getAnnotation(PersonalData.class);
                if (annotation != null) {
                    String category = annotation.category();
                    if (!registeredKeys.contains(category)) {
                        log.error("PersonalDataCollectorに未登録のカテゴリ: class={}, category={}",
                                clazz.getSimpleName(), category);
                    }
                }
            } catch (ClassNotFoundException e) {
                log.warn("クラスロード失敗: {}", candidate.getBeanClassName());
            }
        }

        log.info("PersonalDataCoverageValidator: 検証完了 (登録済みカテゴリ数={})", registeredKeys.size());
    }
}
