package com.luyi.myfirst;

import com.luyi.myfirst.common.web.GlobalExceptionHandler;
import com.luyi.myfirst.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：验证 Spring 容器能真实装配起来。
 * 编译通过只是第一层，容器起不来照样算不合格，所以这一层必须留在 Harness 里。
 *
 * 断言对象是认证侧的基础设施：配置绑定、全局异常处理、数据源。
 * 这里不写具体端点——端点的可用性由各自的契约测试负责，混进来只会让失败原因变模糊。
 */
@SpringBootTest
class ApplicationContextTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("Spring 容器加载：认证配置、全局异常处理与数据源均已装配")
    void contextLoadsWithAuthInfrastructure() {
        assertThat(context.getBean(AuthProperties.class)).isNotNull();
        assertThat(context.getBean(GlobalExceptionHandler.class)).isNotNull();
        assertThat(context.getBean(DataSource.class)).isNotNull();
    }
}
