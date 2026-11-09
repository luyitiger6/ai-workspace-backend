package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.config.WriteRateLimitProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写限流的控制器级接线（tasks 2.3 / 2.4）：拦截器真的进了 MVC 链路，而不只是存在。
 *
 * 额度压到 3 / 2：要证的是「哪条规则在什么位置生效」，额度多大不影响结论，
 * 而每个放行的写请求都要走完一次真实请求链路。
 *
 * 未登录的 {@code POST /api/posts} 是最好的探针：它为 401 时说明请求走到了凭证判定，
 * 为 429 时说明限流在凭证判定之前就短路了——状态码本身就是拦截器顺序的证据。
 * 若把 {@link WriteRateLimitInterceptor#ORDER} 调到 {@link AuthInterceptor#ORDER} 之后，
 * 本类的第一条用例会先失败，而不是靠读代码维护顺序。
 *
 * 计数活在共享 Redis 里，数据库回滚救不了它：每个用例前后都按前缀清一遍。
 */
@SpringBootTest(properties = {
        "wanderchain.rate-limit.write.max=3",
        "wanderchain.rate-limit.write.unknown-max=2"
})
@AutoConfigureMockMvc
class WriteRateLimitChainIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String OTHER_ORIGIN = "http://evil.example.com";
    private static final String CLIENT = "203.0.113.7";
    private static final String OTHER_CLIENT = "203.0.113.8";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WriteRateLimitProperties limits;

    @Autowired
    private StringRedisTemplate redis;

    @AfterEach
    void clearCounters() {
        Set<String> keys = redis.keys(WriteRateLimitInterceptor.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("写端点超限：前 N 次走到凭证判定（401），第 N+1 次在它之前得到 429 + Retry-After")
    void writeEndpointRejectsAfterQuota() throws Exception {
        for (int i = 0; i < limits.max(); i++) {
            write(CLIENT, ORIGIN)
                    .andExpect(status().isUnauthorized());
        }

        MvcResult limited = write(CLIENT, ORIGIN)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();

        long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, limits.window().toSeconds());
    }

    @Test
    @DisplayName("GET /api/posts 不计数：读多少遍都不影响随后的写额度")
    void readsDoNotConsumeWriteQuota() throws Exception {
        for (int i = 0; i < limits.max() * 3; i++) {
            mockMvc.perform(MockMvcRequestBuilders.get("/api/posts")
                            .header("X-Forwarded-For", CLIENT))
                    .andExpect(status().isOk());
        }

        write(CLIENT, ORIGIN).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/api/auth/** 不消耗写额度：认证端点自己有桶，不占全站写额度")
    void authEndpointsDoNotConsumeWriteQuota() throws Exception {
        for (int i = 0; i < limits.max() * 3; i++) {
            mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                            .header("X-Forwarded-For", CLIENT)
                            .header(HttpHeaders.ORIGIN, ORIGIN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@example.com\",\"password\":\"wrong-password\"}"))
                    // 凭证错误的具体状态码不是本用例的断言面：只要求它不进写桶
                    .andReturn();
        }

        write(CLIENT, ORIGIN).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("跨站请求先 403 且不消耗额度：来源校验排在写限流之前")
    void crossSiteRequestIsRejectedBeforeCounting() throws Exception {
        for (int i = 0; i < limits.max(); i++) {
            write(CLIENT, OTHER_ORIGIN)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CROSS_SITE_REJECTED"));
        }

        // 前 3 次若消耗了额度，这里会是第一个 429；为 401 才说明它们根本没进计数
        write(CLIENT, ORIGIN).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("未知来源独立额度：没有转发头的请求落 unknown 桶，先于已解析来源被拒")
    void unknownBucketIsLimitedSeparately() throws Exception {
        for (int i = 0; i < limits.unknownMax(); i++) {
            write(null, ORIGIN).andExpect(status().isUnauthorized());
        }

        write(null, ORIGIN)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // 未知桶耗尽不影响已解析来源
        write(OTHER_CLIENT, ORIGIN).andExpect(status().isUnauthorized());
    }

    /**
     * 发一个写请求。{@code forwardedFor} 为 null 表示不带转发头：
     * 对端回环在 trusted-proxies 里，但拿不出可采信的最后一跳，因此归入 unknown 桶。
     */
    private ResultActions write(String forwardedFor, String origin) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.post("/api/posts")
                .header(HttpHeaders.ORIGIN, origin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
        if (forwardedFor != null) {
            builder.header("X-Forwarded-For", forwardedFor);
        }
        return mockMvc.perform(builder);
    }
}
