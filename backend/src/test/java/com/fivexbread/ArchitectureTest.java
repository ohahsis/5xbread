package com.fivexbread;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.springframework.transaction.annotation.Transactional;

/**
 * 패키지 의존 규칙 (decisions.md 2장 "패키지 구조").
 * domain 규칙은 위반 0 을 강제한다. 나머지는 지금 있는 위반을 archunit_store 허용 목록에 얼려 두고 새 위반만 막는다.
 * 허용 목록은 줄이기만 한다.
 */
@AnalyzeClasses(packages = "com.fivexbread", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /** 클래스나 메서드 하나에라도 @Transactional 이 붙은 클래스. 직접 의존만 본다(Redis 를 쓰는 빈을 거쳐 부르는 것은 못 잡음) */
    private static final DescribedPredicate<JavaClass> HAS_TRANSACTIONAL = DescribedPredicate.describe(
            "have @Transactional on the class or a method",
            c -> c.isAnnotatedWith(Transactional.class)
                    || c.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(Transactional.class)));

    /** 허용 목록이 비어(2026-11-27) freeze 를 뺐다. 위반 0 을 강제한다 */
    @ArchTest
    static final ArchRule domain은_inbound와_config에_의존하지_않는다 =
            noClasses().that().resideInAPackage("com.fivexbread.domain..")
                    .should().dependOnClassesThat().resideInAnyPackage("com.fivexbread.inbound..", "com.fivexbread.config..");

    @ArchTest
    static final ArchRule config는_inbound에_의존하지_않는다 = FreezingArchRule.freeze(
            noClasses().that().resideInAPackage("com.fivexbread.config..")
                    .should().dependOnClassesThat().resideInAPackage("com.fivexbread.inbound.."));

    @ArchTest
    static final ArchRule 트랜잭션_클래스는_Redis에_의존하지_않는다 = FreezingArchRule.freeze(
            noClasses().that(HAS_TRANSACTIONAL)
                    .should().dependOnClassesThat().resideInAPackage("org.springframework.data.redis.."));
}
