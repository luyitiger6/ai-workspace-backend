package com.luyi.myfirst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：验证 Spring 容器能真实装配起来。
 * 编译通过只是第一层，容器起不来照样算不合格，所以这一层必须留在 Harness 里。
 */
@SpringBootTest
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Spring 容器加载：Web 层与领域层均已装配")
    void contextLoadsWithWebAndDomainBeans() {
        assertThat(context.getBean("todoController")).isNotNull();
        assertThat(context.getBean("todoService")).isNotNull();
    }
}
