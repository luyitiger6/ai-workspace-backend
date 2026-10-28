package com.luyi.myfirst.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import com.luyi.myfirst.common.security.PasswordHasher;
import com.luyi.myfirst.dao.auth.entity.Account;
import com.luyi.myfirst.dao.auth.repository.AccountRepository;
import com.luyi.myfirst.web.auth.dto.LoginRequest;
import com.luyi.myfirst.web.auth.dto.RegisterRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缓存（Redis）不可达时，注册与登录链路整体失败而非静默放行（tasks 8.5）。
 *
 * 「整体失败」的反面是「静默放行」——缓存连不上时把 verify 当成「键不存在」返回、
 * 或把限流计数当成 0 放行，都会让本该被拦的请求蒙混过关。这里把 Redis 指到一个
 * 无人监听的端口，断言两条链路都以 5xx 收场，且不产生任何副作用（不建账户、不发令牌）。
 *
 * 为什么必须用 RANDOM_PORT + TestRestTemplate，而不是 MockMvc：
 * Redis 连不上抛的是 RedisConnectionFailureException（不是 BizException），
 * {@link com.luyi.myfirst.common.web.GlobalExceptionHandler} 只收口 BizException，
 * 这个异常一路冒到容器 → 500。而 MockMvc 对未处理异常是「重新抛出」而非渲染容器错误页
 * （见 ErrorResponseLeakTest 6.5 的同款取舍），用 MockMvc 只会得到 perform() 抛异常、
 * 观察不到真实的 5xx。所以要起真实服务器，用 TestRestTemplate 收响应。
 *
 * 端口取「刚释放的空闲端口」：ServerSocket(0) 让 OS 分配一个端口后立刻关闭，
 * 该端口在 127.0.0.1 上随即无人监听 → 连接被立即拒绝（连接拒绝是即时的，不会耗尽
 * application.yml 里的 timeout=2s）。这比写死一个端口号可靠：写死的端口可能正好被别的
 * 进程占着，那 Redis 反而连得上，测试就成了永真的空断言。
 *
 * 两条链路各自「失败在写操作之前」，这一点由实现顺序保证、也正是本测试要钉住的效果：
 * - 注册：RegisterServiceImpl 的顺序是 格式 → verify（Redis）→ 建账户 → 签发令牌。
 *   verify 里的 redis.execute 是第一个 Redis 调用，它一抛，后面的 accounts.save 根本不会执行
 *   → 5xx + 查不到账户 + 无 Set-Cookie。
 * - 登录：LoginServiceImpl 的第 0 步 isSourceBlocked 就是 Redis 调用，排在格式校验、
 *   账户查询与密码比对之前 → 即便账户存在、密码正确，也在签发令牌之前失败
 *   → 5xx + 无 Set-Cookie（令牌只经 Set-Cookie 下发，响应体里检索不到它）。
 *
 * 决策：不加 @Transactional。RANDOM_PORT 下服务器跑在另一个线程，测试线程的事务回滚
 * 管不到服务器线程的写；而且登录用例要种一个「已提交」的 active 账户，才能证明挡住它的
 * 确实是缓存不可达、而不是「账户不存在/密码不对」这类本来就会失败的原因。种了的账户由
 * @AfterEach 显式删干净（唯一 email，删它不影响任何其它用例）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RedisUnavailableIntegrationTest {

    /** OriginGuard 先于一切：POST 必须带一个已登记的 Origin，否则先撞 403 CROSS_SITE_REJECTED，
     *  根本到不了 Redis。这个值取自 application.yml 的 allowed-origins。 */
    private static final String ORIGIN = "http://localhost:3000";
    private static final String PASSWORD = "correct-horse-battery";

    /** 唯一 email：注册链路断言「查不到账户」，任何历史/并发数据撞上都会让断言失真。 */
    private static final String REGISTER_EMAIL =
            "redis-down-register-" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
    private static final String LOGIN_EMAIL =
            "redis-down-login-" + UUID.randomUUID().toString().replace("-", "") + "@example.com";

    /** 非空的 6 位码即可：verify 只挡 null/blank，随后就撞上 Redis，不会真去比对码值。 */
    private static final String SIX_DIGIT_CODE = "123456";

    /** 释放后的空闲端口：127.0.0.1 上无人监听，连接立即被拒。 */
    private static final int DEAD_REDIS_PORT = reserveThenReleasePort();

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountRepository accounts;
    @Autowired private PasswordHasher hasher;

    @LocalServerPort private int port;

    /** 本用例种下的、需要在结束时删掉的 email（防御性清理：即便实现有 bug 真建了账户也能收走）。 */
    private final List<String> emailsToClean = new ArrayList<>();

    @DynamicPropertySource
    static void pointRedisAtDeadPort(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> DEAD_REDIS_PORT);
    }

    @AfterEach
    void cleanSeededAccounts() {
        for (String email : emailsToClean) {
            accounts.findByEmailAndDeletedAtIsNull(email).ifPresent(accounts::delete);
        }
        emailsToClean.clear();
    }

    @Test
    @DisplayName("Redis 不可达：注册整体失败——5xx、不创建账户、不下发令牌")
    void registerFailsClosedWhenRedisIsDown() throws Exception {
        // 先登记再发请求：万一实现没有「失败在写之前」，真建出了账户，@AfterEach 也能把它删掉，
        // 不留下污染后续用例的脏数据。
        emailsToClean.add(REGISTER_EMAIL);

        RegisterRequest body = new RegisterRequest(REGISTER_EMAIL, SIX_DIGIT_CODE, PASSWORD, "Doomed");
        ResponseEntity<String> response = post("/api/auth/register", body);

        assertThat(response.getStatusCode().is5xxServerError())
                .as("缓存不可达时注册必须整体失败为 5xx，而不是静默放行；实际=%s", response.getStatusCode())
                .isTrue();
        assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE))
                .as("失败响应不得下发会话 cookie（令牌只经 Set-Cookie 下发）")
                .isFalse();
        assertThat(accounts.findByEmailAndDeletedAtIsNull(REGISTER_EMAIL))
                .as("注册在 verify（Redis）阶段就失败，账户创建的 DB 写不该发生")
                .isEmpty();
    }

    @Test
    @DisplayName("Redis 不可达：登录整体失败——即便账户存在、密码正确也是 5xx，不签发令牌")
    void loginFailsClosedWhenRedisIsDown() throws Exception {
        // 种一个已提交的 active 账户，密码正是下面登录要用的那个。只有「本来能登录成功」的账户，
        // 才能证明挡住这次登录的确实是缓存不可达（第 0 步 isSourceBlocked），
        // 而不是「账户不存在」或「密码不对」这类本来就会失败的原因。
        accounts.saveAndFlush(Account.createActive(
                Ulid.generate(), LOGIN_EMAIL, hasher.hash(PASSWORD), "Seeded"));
        emailsToClean.add(LOGIN_EMAIL);

        LoginRequest body = new LoginRequest(LOGIN_EMAIL, PASSWORD, null, null);
        ResponseEntity<String> response = post("/api/auth/login", body);

        assertThat(response.getStatusCode().is5xxServerError())
                .as("缓存不可达时登录必须整体失败为 5xx（第 0 步 isSourceBlocked 就是 Redis 调用）；实际=%s",
                        response.getStatusCode())
                .isTrue();
        assertThat(response.getHeaders().containsKey(HttpHeaders.SET_COOKIE))
                .as("登录在签发令牌之前就失败，不得下发会话 cookie")
                .isFalse();
        // 账户仍在、且没被动过：缓存故障从不改账户状态（与 8.2「限流不改变账户状态」同一口径）。
        assertThat(accounts.findByEmailAndDeletedAtIsNull(LOGIN_EMAIL))
                .as("登录失败不得改动或删掉账户")
                .isPresent();
    }

    /** 发一个带合法 Origin 的 JSON POST，用真实服务器收响应（TestRestTemplate 不因 4xx/5xx 抛异常）。 */
    private ResponseEntity<String> post(String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ORIGIN, ORIGIN);
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(body), headers);
        return restTemplate.exchange(
                "http://localhost:" + port + path, HttpMethod.POST, entity, String.class);
    }

    /** 拿一个 OS 分配的空闲端口，随即释放：返回值那一刻它已无人监听，连上去必被拒。 */
    private static int reserveThenReleasePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("无法分配一个用于模拟 Redis 不可达的空闲端口", e);
        }
    }
}
