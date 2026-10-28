package com.luyi.myfirst.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.ControllerAdviceBean;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 守卫在真实应用里确实被装配成出站通道（tasks 6.1 的装配一层）。
 *
 * {@link AuthResponseGuardTest} 直接调 {@code beforeBodyWrite}，证明了「未标记的 /api/auth 响应体会被抛异常拦下」
 * 这条判定逻辑；但它证明不了「Spring 会不会真的把守卫应用到响应体出站」。本类在真实上下文里补上这一半。
 *
 * 这里刻意不造一个「返回未标记体的探针端点」去跑端到端：探针要落在 {@code /api/auth/**} 才归守卫管，
 * 而任何带 {@code @Controller} 立体注解的嵌套探针都会被别的 {@code @SpringBootTest} 上下文组件扫描，
 * 把 {@code /api/auth/probe-raw} 混进 {@code SimulatedSenderGuardTest} 那份「/api/auth 端点恰为九个」的白名单；
 * 只标 {@code @RequestMapping} 不标 {@code @Controller} 又不会被 {@code RequestMappingHandlerMapping} 认成处理器（实测 404）。
 * 两头堵死，所以改用装配证据：守卫是 {@code @ControllerAdvice} bean（{@code RequestMappingHandlerAdapter}
 * 据此把它收进 {@code ResponseBodyAdvice} 链、应用到每个响应体），且它确实实现了 {@code ResponseBodyAdvice}。
 * 「应用后未标记体被拦下抛异常」这半条由单元测试钉住，两层合起来等价于一次端到端，却没有脆弱探针。
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthResponseGuardWiringTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("守卫被注册为 bean：@RestControllerAdvice 让它进了容器，才可能被 MVC 应用")
    void guardIsRegisteredAsBean() {
        assertThat(context.getBeanNamesForType(AuthResponseGuard.class)).isNotEmpty();
    }

    @Test
    @DisplayName("守卫在 @ControllerAdvice 集合里且实现 ResponseBodyAdvice：Spring 会把它应用到每个响应体出站")
    void guardIsCollectedAsControllerAdviceAndIsResponseBodyAdvice() {
        // RequestMappingHandlerAdapter 初始化时正是用 findAnnotatedBeans 收集 @ControllerAdvice，
        // 再从中挑出 ResponseBodyAdvice 织入消息转换器。守卫出现在这个集合里，等于它进了真实出站链。
        List<ControllerAdviceBean> adviceBeans = ControllerAdviceBean.findAnnotatedBeans(context);
        boolean guardIsAdvice = adviceBeans.stream()
                .anyMatch(bean -> AuthResponseGuard.class.equals(bean.getBeanType()));
        assertThat(guardIsAdvice)
                .as("守卫不在 @ControllerAdvice 集合里，Spring 不会把它应用到响应体出站")
                .isTrue();

        // findAnnotatedBeans 只认 @ControllerAdvice 注解；真正改写响应体还要求它实现 ResponseBodyAdvice。
        // 两个条件都满足，才能断定「守卫会被当作出站通道调用」，而不只是「它是个 advice bean」。
        assertThat(context.getBean(AuthResponseGuard.class)).isInstanceOf(ResponseBodyAdvice.class);
    }

    @Test
    @DisplayName("真实的已标记端点照常 200：守卫应用后不误伤合法响应")
    void realMarkedEndpointStillFlowsThroughTheGuard() throws Exception {
        // captcha 是公开端点，返回已标记的 CaptchaResponse。守卫应用后它仍须 200——
        // 若守卫把合法标记响应也拦下，这里立刻红，证明「唯一出站通道」没有误伤正常端点。
        mockMvc.perform(get("/api/auth/captcha"))
                .andExpect(status().isOk());
    }
}
