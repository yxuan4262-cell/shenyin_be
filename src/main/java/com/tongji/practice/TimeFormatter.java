package com.tongji.practice;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 练习 2 目标类：故意不加任何 Spring 注解，
 * 由 PracticeConfig 中的 @Bean 方法手动注册进容器。
 */
public class TimeFormatter {

    private static final DateTimeFormatter PATTERN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public String format(LocalDateTime time) {
        return time == null ? "" : PATTERN.format(time);
    }
}
