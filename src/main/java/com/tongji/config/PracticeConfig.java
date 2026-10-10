package com.tongji.config;

import com.tongji.practice.TimeFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 练习 2：通过 @Bean 方法把 TimeFormatter 手动注册进容器。
 */
@Configuration
public class PracticeConfig {

    /**
     * 方法返回值即 Bean 实例，Bean 名默认为方法名 "timeFormatter"。
     */
    @Bean
    public TimeFormatter timeFormatter() {
        return new TimeFormatter();
    }
}
