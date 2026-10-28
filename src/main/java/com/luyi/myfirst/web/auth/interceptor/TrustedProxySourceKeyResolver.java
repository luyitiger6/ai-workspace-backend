package com.luyi.myfirst.web.auth.interceptor;

import com.luyi.myfirst.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 来源键：只采信已登记代理层追加的那一跳（spec「登录失败以来源限流代替账户锁定」）。
 *
 * 四条规则，每条都对应一种「看起来在工作、其实没用」的错误做法：
 *
 * 1. 先看对端地址。对端不在 {@code trusted-proxies} 里，这次请求就是直达的，
 *    它自带的 X-Forwarded-For 一律不看。无条件采信的话，伪造一个 header 就能无限刷新额度。
 * 2. 对端可信时取 X-Forwarded-For 的**最后一跳**。每一层代理追加的是它自己看到的
 *    对端地址，所以只有最右一跳是本项目这层代理写的；它左边的部分完全由调用方控制。
 * 3. 取不到、解析不出、格式非法一律归入「未知来源」单桶。解析失败不等于放行——
 *    spec 明确这两个 MUST：不与已解析来源合并，也不因此跳过限流。
 * 4. IPv6 按 /64 聚合。一个用户手里握着整个 /64，逐地址计数等于没有限流。
 *
 * 本类只认字面量：转发头是调用方可控的文本，交给 {@link InetAddress} 之前必须先被
 * 正则约束成「只含十六进制与冒号」，否则一个像域名的值会让它在解析线程里发起 DNS 查询。
 */
@Component
public class TrustedProxySourceKeyResolver implements SourceKeyResolver {

    /** 未知来源桶的键。测试据它构造「来源已被限流」的场景。 */
    public static final String UNKNOWN_SOURCE = "unknown";

    private static final String FORWARDED_FOR = "X-Forwarded-For";

    /** 转发头长度上限：超长值直接弃用，不让调用方决定我们要解析多少字节。 */
    private static final int MAX_HEADER_LENGTH = 1024;

    private static final int IPV6_PREFIX_BITS = 64;

    /** 单个地址字面量的长度上限。<c>::</c> 压缩写法最长也远小于此。 */
    private static final int MAX_ADDRESS_LENGTH = 64;

    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** 只允许十六进制与冒号：匹配到它就必然是字面量，InetAddress 不会去查域名。 */
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:]+$");

    private final List<Cidr> trusted;

    public TrustedProxySourceKeyResolver(AuthProperties properties) {
        this.trusted = properties.trustedProxies().stream().map(Cidr::parse).toList();
    }

    @Override
    public String resolve(HttpServletRequest request) {
        byte[] peer = parseLiteral(request.getRemoteAddr());
        if (peer == null || !isTrusted(peer)) {
            return UNKNOWN_SOURCE;
        }
        byte[] client = lastForwardedHop(request.getHeader(FORWARDED_FOR));
        return client == null ? UNKNOWN_SOURCE : keyOf(client);
    }

    private boolean isTrusted(byte[] address) {
        for (Cidr cidr : trusted) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取最右一跳。缺失、为空、超长、解析失败都回 null，由调用方归入未知桶。
     *
     * 最后一跳为空的写法（结尾多一个逗号）不补位取前一跳：那是调用方能写的内容，
     * 补位等于把一个本该弃用的值当成了可信地址。
     */
    private static byte[] lastForwardedHop(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.isEmpty() || value.length() > MAX_HEADER_LENGTH) {
            return null;
        }
        return parseLiteral(value.substring(value.lastIndexOf(',') + 1));
    }

    /** 解析一个地址字面量；不是字面量就回 null（绝不触发域名解析）。 */
    private static byte[] parseLiteral(String text) {
        if (text == null) {
            return null;
        }
        String value = text.trim();
        if (value.isEmpty() || value.length() > MAX_ADDRESS_LENGTH) {
            return null;
        }
        // 作用域 id（fe80::1%eth0）不属于地址本身，先去掉再判
        int scope = value.indexOf('%');
        if (scope >= 0) {
            value = value.substring(0, scope);
        }
        if (IPV4.matcher(value).matches()) {
            return parseIpv4(value);
        }
        if (!value.contains(":")) {
            return null;
        }
        // IPv4-mapped（::ffff:1.2.3.4）：同一台主机换个写法不该换来一个新桶。
        // 只允许这一段带点，其余形态必须整段是十六进制，避免把域名当字面量解析。
        int lastColon = value.lastIndexOf(':');
        String tail = value.substring(lastColon + 1);
        if (IPV4.matcher(tail).matches()) {
            return parseIpv4(tail);
        }
        if (!IPV6.matcher(value).matches()) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] parseIpv4(String value) {
        Matcher matcher = IPV4.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        byte[] address = new byte[4];
        for (int i = 0; i < 4; i++) {
            String octet = matcher.group(i + 1);
            // 前导零在部分解析器里会被当成八进制：含糊的写法不猜，直接判非法
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                return null;
            }
            int parsed = Integer.parseInt(octet);
            if (parsed > 255) {
                return null;
            }
            address[i] = (byte) parsed;
        }
        return address;
    }

    /** 限流键。IPv6 只保留前 64 位，因此同一 /64 下的地址得到同一个键。 */
    private static String keyOf(byte[] address) {
        if (address.length == 4) {
            return "ip:" + (address[0] & 0xFF) + "." + (address[1] & 0xFF) + "."
                    + (address[2] & 0xFF) + "." + (address[3] & 0xFF);
        }
        return String.format(Locale.ROOT, "ip:%x:%x:%x:%x::/%d",
                group(address, 0), group(address, 2), group(address, 4), group(address, 6),
                IPV6_PREFIX_BITS);
    }

    private static int group(byte[] address, int index) {
        return ((address[index] & 0xFF) << 8) | (address[index + 1] & 0xFF);
    }

    /**
     * 一条登记项：单个地址或 CIDR 网段。
     *
     * 解析失败直接抛出而不是跳过：配置里一个写错的网段，如果被静默丢弃，
     * 那条代理之后的请求就全落到未知桶——现象是「全站共用一份额度」，
     * 而排查方向会被一个「没报错」的配置项带偏。这与 auth.jwt-secret 的
     * 「配了但没生效要变成启动失败」是同一条约定。
     */
    private record Cidr(byte[] network, int bits) {

        static Cidr parse(String text) {
            String value = text == null ? "" : text.trim();
            int slash = value.indexOf('/');
            String literal = slash < 0 ? value : value.substring(0, slash);
            byte[] network = parseLiteral(literal);
            if (network == null) {
                throw new IllegalStateException(
                        "wanderchain.auth.trusted-proxies 无法解析: " + text);
            }
            int bits = slash < 0 ? network.length * 8 : parseBits(value.substring(slash + 1), text);
            if (bits > network.length * 8) {
                throw new IllegalStateException(
                        "wanderchain.auth.trusted-proxies 前缀长度越界: " + text);
            }
            return new Cidr(network, bits);
        }

        private static int parseBits(String text, String original) {
            try {
                int bits = Integer.parseInt(text.trim());
                if (bits < 0) {
                    throw new IllegalStateException(
                            "wanderchain.auth.trusted-proxies 前缀长度非法: " + original);
                }
                return bits;
            } catch (NumberFormatException e) {
                throw new IllegalStateException(
                        "wanderchain.auth.trusted-proxies 前缀长度非法: " + original, e);
            }
        }

        boolean contains(byte[] candidate) {
            // 家族不同不相交：IPv4 的 4 字节不会被拿来和 IPv6 的 16 字节比前几位
            if (candidate.length != network.length) {
                return false;
            }
            int wholeBytes = bits / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainder = bits % 8;
            if (remainder == 0) {
                return true;
            }
            int mask = 0xFF << (8 - remainder);
            return (candidate[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
