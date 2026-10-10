package com.tongji.practice;

import org.springframework.stereotype.Component;

/**
 * 练习 1：通过 @Component 自动扫描注册。
 * <p>
 * Spring 启动时扫描 com.tongji 包，发现此注解后自动创建实例放入容器。
 */
@Component
public class GreetingService {

    public String greet(String name) {
        return "Hello, " + name + "! Welcome to Spring IoC.";
    }
}
