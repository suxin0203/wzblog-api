package com.wzblog;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层与模块边界守护（架构 v2 §3.1 两条硬规则 + 06 §7 质量门禁第 2 条）：
 * 1. api 层不依赖 infra（不出现 Mapper/存储实现）；
 * 2. domain 层不依赖 app/infra/api、不依赖 Web 与 MyBatis；
 * 3. common 不反向依赖业务模块；
 * 4. 跨模块只能依赖对方 app 层（端口与服务）或 domain 事件，禁止触达对方 infra/api/domain 内部。
 */
@AnalyzeClasses(packages = "com.wzblog", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String MODULES_PREFIX = "com.wzblog.modules.";

    @ArchTest
    static final ArchRule apiMustNotDependOnInfra = noClasses()
            .that().resideInAPackage("..modules..api..")
            .should().dependOnClassesThat().resideInAPackage("..modules..infra..");

    /**
     * domain 允许 MyBatis-Plus 的纯映射注解（@TableName/@TableId），
     * 禁止其 core 机制与 MyBatis/Servlet API 进入领域层。
     */
    @ArchTest
    static final ArchRule domainMustStayIsolated = noClasses()
            .that().resideInAPackage("..modules..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..modules..app..", "..modules..infra..", "..modules..api..",
                    "..springframework..web..", "..baomidou..core..", "..apache..ibatis..");

    @ArchTest
    static final ArchRule commonMustNotDependOnModules = noClasses()
            .that().resideInAPackage("..common..")
            .should().dependOnClassesThat().resideInAPackage("..modules..");

    @ArchTest
    static final ArchRule crossModuleOnlyThroughAppOrEvents = classes()
            .that().resideInAPackage("..modules..")
            .should(onlyDependOnOtherModulesAppLayerOrEvents());

    private static ArchCondition<JavaClass> onlyDependOnOtherModulesAppLayerOrEvents() {
        return new ArchCondition<>("only depend on other modules' app layer or domain events") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                String sourceModule = moduleOf(clazz);
                if (sourceModule == null) {
                    return;
                }
                clazz.getDirectDependenciesFromSelf().forEach(dependency -> {
                    JavaClass target = dependency.getTargetClass();
                    String targetModule = moduleOf(target);
                    if (targetModule == null || targetModule.equals(sourceModule)) {
                        return;
                    }
                    if (isAllowedCrossModuleTarget(target)) {
                        return;
                    }
                    events.add(SimpleConditionEvent.violated(clazz,
                            String.format("%s -> %s 跨模块仅允许对方 app 层或 domain 事件（%s）",
                                    clazz.getName(), target.getName(), dependency.getDescription())));
                });
            }
        };
    }

    private static boolean isAllowedCrossModuleTarget(JavaClass target) {
        String subPackage = moduleSubPackage(target);
        if ("app".equals(subPackage)) {
            return true;
        }
        // domain 事件允许被订阅；domain 其他内部类不允许跨模块触达
        return "domain".equals(subPackage)
                && target.getPackageName().matches(".*\\.domain\\.event(\\..*)?");
    }

    private static String moduleOf(JavaClass clazz) {
        String name = clazz.getPackageName();
        if (!name.startsWith(MODULES_PREFIX)) {
            return null;
        }
        String rest = name.substring(MODULES_PREFIX.length());
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    private static String moduleSubPackage(JavaClass clazz) {
        String name = clazz.getPackageName();
        if (!name.startsWith(MODULES_PREFIX)) {
            return null;
        }
        String rest = name.substring(MODULES_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String sub = rest.substring(dot + 1);
        int next = sub.indexOf('.');
        return next < 0 ? sub : sub.substring(0, next);
    }
}
