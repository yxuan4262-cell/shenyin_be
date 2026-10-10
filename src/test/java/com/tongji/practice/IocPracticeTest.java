package com.tongji.practice;

import com.tongji.config.PracticeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第二阶段练习：验证两种 Bean 注册方式。
 * <p>
 * 使用裸 Spring 容器（不启动 Spring Boot、不依赖 MySQL/Redis 等中间件），
 * 直观观察 IoC 容器的注册与获取过程。
 */
class IocPracticeTest {

    @Test
    void componentScanAndBeanMethodBothRegisterBeans() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            // 扫描 com.tongji.practice 包 → 发现 @Component 的 GreetingService
            ctx.scan("com.tongji.practice");
            // 注册配置类 → 发现其中的 @Bean 方法
            ctx.register(PracticeConfig.class);
            ctx.refresh();

            // 两种注册方式产出的 Bean 都能按类型从容器取出
            GreetingService greeting = ctx.getBean(GreetingService.class);
            TimeFormatter formatter = ctx.getBean(TimeFormatter.class);

            assertThat(greeting).isNotNull();
            assertThat(formatter).isNotNull();
            assertThat(greeting.greet("知光")).contains("Hello");
            assertThat(formatter.format(LocalDateTime.of(2026, 8, 16, 10, 30, 0)))
                    .isEqualTo("2026-08-16 10:30:00");

            // @Bean 注册的 Bean 名默认为方法名
            assertThat(ctx.containsBean("timeFormatter")).isTrue();
        }
    }
}
