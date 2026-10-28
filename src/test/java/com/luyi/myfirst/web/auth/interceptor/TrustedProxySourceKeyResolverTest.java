package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.config.AuthProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 来源解析的规则（tasks 5.9）：「只采信已登记代理层追加的那一跳」不是一句注释。
 *
 * 每条用例都对着一种「看起来在工作、其实没用」的写法：
 * 直信转发头（伪造即扩容）、取第一跳（调用方写的）、解析失败就放行（未知来源白名单化）、
 * 逐地址计数（IPv6 一个 /64 等于无限额度）。
 */
class TrustedProxySourceKeyResolverTest {

    private static final String UNKNOWN = TrustedProxySourceKeyResolver.UNKNOWN_SOURCE;
    private static final String TRUSTED_PEER = "10.1.2.3";

    @Test
    @DisplayName("直达请求自带转发头：不采信，仍归未知来源")
    void directRequestCarryingForwardedHeaderIsUnknown() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");

        assertThat(resolver.resolve(request("203.0.113.9", "198.51.100.7"))).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("对端可信但没带转发头：未知来源，不退回对端地址")
    void trustedPeerWithoutForwardedHeaderIsUnknown() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");

        assertThat(resolver.resolve(request(TRUSTED_PEER, null))).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("对端可信时只取最右一跳：左边那段是调用方写的")
    void takesOnlyTheHopAppendedByOurProxy() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");

        assertThat(resolver.resolve(request(TRUSTED_PEER, "1.2.3.4, 198.51.100.7")))
                .isEqualTo("ip:198.51.100.7");
    }

    @Test
    @DisplayName("解析不出就不猜：非法、带端口、前导零、空值一律未知来源")
    void unparsableHopsAreUnknown() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");

        assertThat(resolver.resolve(request(TRUSTED_PEER, "not-an-ip"))).as("非字面量").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, "300.1.2.3"))).as("越界").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, "01.2.3.4"))).as("前导零").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, "198.51.100.7:5678"))).as("带端口").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, "1.2.3"))).as("缺段").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, ""))).as("空头").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, "1.2.3.4,"))).as("末尾空跳").isEqualTo(UNKNOWN);
        assertThat(resolver.resolve(request(TRUSTED_PEER, "exa" + "mple.com")))
                .as("像域名的值不该被拿去解析").isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("超长转发头直接弃用：解析多少字节不由调用方决定")
    void oversizedForwardedHeaderIsUnknown() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");
        String huge = "1.2.3.4, ".repeat(200);

        assertThat(resolver.resolve(request(TRUSTED_PEER, huge))).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("IPv6 按 /64 聚合：同前缀的不同地址得到同一个键")
    void ipv6SharesOneKeyPerSlash64() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");

        String first = resolver.resolve(request(TRUSTED_PEER, "2001:db8:aa:bb::1"));
        String second = resolver.resolve(request(TRUSTED_PEER, "2001:db8:aa:bb:1:2:3:4"));
        String other = resolver.resolve(request(TRUSTED_PEER, "2001:db8:aa:cc::1"));

        assertThat(first).isEqualTo("ip:2001:db8:aa:bb::/64");
        assertThat(second).isEqualTo(first);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    @DisplayName("IPv4-mapped 写法并回同一台主机：::ffff:198.51.100.7 与 198.51.100.7 同桶")
    void ipv4MappedCollapsesToTheIpv4Bucket() {
        SourceKeyResolver resolver = resolver("10.0.0.0/8");

        assertThat(resolver.resolve(request(TRUSTED_PEER, "::ffff:198.51.100.7")))
                .isEqualTo(resolver.resolve(request(TRUSTED_PEER, "198.51.100.7")));
    }

    @Test
    @DisplayName("登记单个地址：只有它自己被采信，邻居仍是未知来源")
    void bareAddressEntryMatchesOnlyThatAddress() {
        SourceKeyResolver resolver = resolver("192.168.1.5");

        assertThat(resolver.resolve(request("192.168.1.5", "198.51.100.7")))
                .isEqualTo("ip:198.51.100.7");
        assertThat(resolver.resolve(request("192.168.1.6", "198.51.100.7"))).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("已登记代理可以把 IPv6 前缀整段登记：/48 覆盖其下所有地址")
    void trustedPrefixCoversTheWholeRange() {
        SourceKeyResolver resolver = resolver("2001:db8:ffff::/48");

        assertThat(resolver.resolve(request("2001:db8:ffff:1:2:3:4:5", "198.51.100.7")))
                .isEqualTo("ip:198.51.100.7");
        assertThat(resolver.resolve(request("2001:db8:fffe::1", "198.51.100.7"))).isEqualTo(UNKNOWN);
    }

    @Test
    @DisplayName("登记项写错就让启动失败：静默丢一条等于让那条代理之后的请求全进未知桶")
    void invalidTrustedProxyEntryFailsFast() {
        assertThatThrownBy(() -> resolver("not-an-ip"))
                .as("不是字面量").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver("10.0.0.0/33"))
                .as("前缀越界").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> resolver("10.0.0.0/x"))
                .as("前缀不是数字").isInstanceOf(IllegalStateException.class);
    }

    private static SourceKeyResolver resolver(String... trustedProxies) {
        return new TrustedProxySourceKeyResolver(new AuthProperties(
                Duration.ofDays(7),
                "test-only-secret",
                false,
                List.of("http://localhost:3000"),
                List.of(trustedProxies),
                new AuthProperties.Captcha(Duration.ofMinutes(2)),
                new AuthProperties.EmailCode(Duration.ofMinutes(5), Duration.ofSeconds(60), 5, 5),
                new AuthProperties.Login(5, Duration.ofMinutes(10), 20, Duration.ofSeconds(60))));
    }

    private static MockHttpServletRequest request(String peer, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
