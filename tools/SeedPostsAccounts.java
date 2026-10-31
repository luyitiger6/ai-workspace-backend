import com.luyi.myfirst.Application;
import com.luyi.myfirst.common.security.PasswordRules;
import com.luyi.myfirst.service.auth.RegisterCodeService;
import com.luyi.myfirst.service.auth.RegisterService;
import com.luyi.myfirst.service.auth.impl.CaptchaServiceImpl;
import com.luyi.myfirst.service.auth.support.CodePurpose;
import com.luyi.myfirst.service.auth.support.OutboxVerificationCodeSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/** 仅用于用户放行的本地账户初始化；不属于应用扫描范围，不提供 HTTP 接口。 */
public final class SeedPostsAccounts {
    private static final Logger LOG = LoggerFactory.getLogger(SeedPostsAccounts.class);
    private static final String LOCAL_DB = "jdbc:mysql://127.0.0.1:8806/wanderchain?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=utf8&allowPublicKeyRetrieval=true&useSSL=false";
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private SeedPostsAccounts() { }

    public static void main(String[] args) {
        int completed = 0;
        int exitCode = 1;
        try {
            boolean checkOnly = args.length == 1 && "--check-only".equals(args[0]);
            require(args.length == 0 || checkOnly, "ARGUMENTS_REJECTED");
            preflight();
            List<SeedAccount> accounts = List.of(account("A"), account("B"));
            require(!accounts.get(0).email().equals(accounts.get(1).email()), "ACCOUNT_PAIR_REJECTED");
            require(!accounts.get(0).password().equals(accounts.get(1).password()), "ACCOUNT_PAIR_REJECTED");
            if (checkOnly) {
                LOG.error("SeedPostsAccounts: preflight=OK writes=0");
                System.exit(0);
                return;
            }

            // 强制无 HTTP 服务、无 DDL；初始化器在任何 Bean 刷新之前复核实际解析配置。
            try (var context = new SpringApplicationBuilder(Application.class)
                    .web(WebApplicationType.NONE)
                    .initializers(candidate -> verifyResolved(candidate.getEnvironment()))
                    .run("--spring.main.web-application-type=none",
                            "--spring.profiles.active=posts-account-seed",
                            "--spring.profiles.default=posts-account-seed",
                            "--spring.datasource.url=" + LOCAL_DB,
                            "--spring.data.redis.host=127.0.0.1",
                            "--spring.data.redis.port=6606",
                            "--spring.data.redis.database=3",
                            "--spring.sql.init.mode=never",
                            "--spring.jpa.hibernate.ddl-auto=none",
                            "--spring.main.banner-mode=off",
                            "--logging.level.root=ERROR")) {
                var captcha = context.getBean(CaptchaServiceImpl.class);
                var codes = context.getBean(RegisterCodeService.class);
                var registration = context.getBean(RegisterService.class);
                var outbox = context.getBean(OutboxVerificationCodeSender.class);
                for (SeedAccount account : accounts) {
                    // 已明确授权的测试签发入口；真实消费、冷却、配额和邮箱码比对仍执行。
                    String answer = randomCaptchaAnswer();
                    var challenge = captcha.issue(answer);
                    codes.issue(account.email(), challenge.captchaId(), answer);
                    var message = outbox.messages().stream()
                            .filter(item -> item.email().equals(account.email()) && item.purpose() == CodePurpose.REGISTER)
                            .findFirst().orElseThrow(() -> new GuardFailure("OUTBOX_DELIVERY_MISSING"));
                    registration.register(account.email(), message.code(), account.password(),
                            "Posts local " + account.alias(), Instant.now());
                    completed += 1;
                    LOG.error("SeedPostsAccounts: account={} created=OK", account.alias());
                }
            }
            exitCode = 0;
            LOG.error("SeedPostsAccounts: completed=2");
        } catch (GuardFailure failure) {
            LOG.error("SeedPostsAccounts: rejected={} completed={}", failure.getMessage(), completed);
        } catch (RuntimeException failure) {
            // 不输出异常对象，避免 JDBC 或框架错误携带账户资料；失败不自动重试。
            LOG.error("SeedPostsAccounts: initialization=FAILED type={} completed={} retry=NO",
                    failure.getClass().getSimpleName(), completed);
        }
        System.exit(exitCode);
    }

    private static void preflight() {
        rejectProd(System.getenv("SPRING_PROFILES_ACTIVE"));
        rejectProd(System.getenv("SPRING_PROFILES_DEFAULT"));
        rejectProd(System.getProperty("spring.profiles.active"));
        rejectProd(System.getProperty("spring.profiles.default"));
        checkOverride("SPRING_DATASOURCE_URL", "spring.datasource.url", LOCAL_DB);
        checkOverride("SPRING_DATA_REDIS_HOST", "spring.data.redis.host", "127.0.0.1");
        checkOverride("SPRING_DATA_REDIS_PORT", "spring.data.redis.port", "6606");
        checkOverride("SPRING_DATA_REDIS_DATABASE", "spring.data.redis.database", "3");
        for (String name : List.of("WANDERCHAIN_DB_USER", "WANDERCHAIN_DB_PASSWORD",
                "WANDERCHAIN_REDIS_PASSWORD", "WANDERCHAIN_JWT_SECRET")) {
            required(name);
        }
    }

    private static void verifyResolved(Environment environment) {
        require(!environment.acceptsProfiles(Profiles.of("prod")), "PROD_REJECTED");
        require(LOCAL_DB.equals(environment.getProperty("spring.datasource.url")), "DATABASE_REJECTED");
        require("127.0.0.1".equals(environment.getProperty("spring.data.redis.host")), "REDIS_REJECTED");
        require("6606".equals(environment.getProperty("spring.data.redis.port")), "REDIS_REJECTED");
        require("3".equals(environment.getProperty("spring.data.redis.database")), "REDIS_REJECTED");
        require("never".equals(environment.getProperty("spring.sql.init.mode")), "DDL_REJECTED");
        require("none".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto")), "DDL_REJECTED");
    }

    private static SeedAccount account(String alias) {
        String prefix = "E2E_POSTS_ACCOUNT_" + alias + "_";
        String email = required(prefix + "EMAIL");
        String password = required(prefix + "PASSWORD");
        require(email.matches("wc-posts-" + alias.toLowerCase(java.util.Locale.ROOT)
                + "-[a-f0-9]{32}@example\\.test"), "LOCAL_ACCOUNT_REJECTED");
        require(PasswordRules.isValid(password), "PASSWORD_RULES_REJECTED");
        return new SeedAccount(alias, email, password);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "ENVIRONMENT_INCOMPLETE");
        return value;
    }

    private static void checkOverride(String variable, String property, String expected) {
        for (String value : Arrays.asList(System.getenv(variable), System.getProperty(property))) {
            require(value == null || expected.equals(value), "LOCAL_STORAGE_REJECTED");
        }
    }

    private static void rejectProd(String value) {
        require(value == null || Arrays.stream(value.split("[,;\\s]+"))
                .noneMatch("prod"::equalsIgnoreCase), "PROD_REJECTED");
    }

    private static String randomCaptchaAnswer() {
        StringBuilder result = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            result.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return result.toString();
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new GuardFailure(reason);
    }

    private record SeedAccount(String alias, String email, String password) { }

    private static final class GuardFailure extends RuntimeException {
        private GuardFailure(String reason) { super(reason); }
    }
}
