package com.luyi.myfirst.service.attraction.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.enums.AttractionCollection;
import com.luyi.myfirst.common.enums.AttractionSortBy;
import com.luyi.myfirst.config.AttractionCacheProperties;
import com.luyi.myfirst.service.attraction.model.AttractionPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 景点列表 / 榜单的读缓存。
 *
 * 键分两族，按业务各自命名，不共用一个含糊的 {@code type} 段：
 *
 * <ul>
 *   <li>排序榜单 {@code wc:attraction:ranking:{sortBy}:{city|all}:{limit}}</li>
 *   <li>策展榜单 {@code wc:attraction:collection:{collection}:{city|all}:{limit}}</li>
 * </ul>
 *
 * 城市与条数必须入键：真实调用里同时存在「北京的热门 5 条」与「全城的热门 50 条」，
 * 少任一维度就会出现前者被后者覆盖。{@code wc:} 前缀是共享 Redis 实例上唯一的隔离层，
 * db index 不承担隔离职责。
 *
 * 缓存的是整页结果（{@code items} + {@code total}）：{@code total} 在排序路是 count
 * 的结果、在策展路是截断前成员数，两者必须与 {@code items} 同源同刻。
 *
 * 失效只靠 TTL。景点当前没有任何写入口，「数据变化就清缓存」没有触发点，
 * 因此这里不提供清除方法；将来出现写入口时按前缀扫删即可。
 *
 * 防击穿：未命中时用 Redis 互斥锁保证同一键只有一个请求回源，其余请求在
 * {@code waitTimeout} 内轮询缓存；预算耗尽则各自回源且不回写（回写权只属于持锁者，
 * 否则等待者会互相覆盖）。宁可多查一次库，也不让请求挂住。
 *
 * 降级：读、写、抢锁、反序列化任何一步出问题都只记 WARN 后走数据库——缓存故障
 * 必须表现为「慢一点」，不能表现为 500。
 */
@Component
public class RankingCache {

    private static final Logger log = LoggerFactory.getLogger(RankingCache.class);

    private static final String RANKING_PREFIX = "wc:attraction:ranking:";
    private static final String COLLECTION_PREFIX = "wc:attraction:collection:";
    private static final String LOCK_PREFIX = "wc:attraction:lock:";

    /** 城市省略时写 all，而不是留空：键段数固定，读键时一眼能看出「没按城市过滤」。 */
    private static final String ALL_CITIES = "all";

    /** 等待者的轮询间隔。它是实现细节而非可调参数，所以不进配置。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    /**
     * 释放锁必须「比较令牌后再删」：锁的 TTL 可能先于业务完成而到期并被他人抢到，
     * 裸 DEL 会删掉别人的锁，等于把互斥放开了。
     */
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNED = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AttractionCacheProperties properties;

    public RankingCache(StringRedisTemplate redis, ObjectMapper objectMapper, AttractionCacheProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 排序榜单（hot / rating / new）。 */
    public AttractionPage ranking(AttractionSortBy sortBy, String citySlug, int limit,
                                  Supplier<AttractionPage> loader) {
        String suffix = sortBy.dbValue() + ':' + cityKey(citySlug) + ':' + limit;
        return readThrough(RANKING_PREFIX + suffix, LOCK_PREFIX + suffix, loader);
    }

    /** 策展榜单（first-trip / no-booking / open-monday）。 */
    public AttractionPage collection(AttractionCollection collection, String citySlug, int limit,
                                     Supplier<AttractionPage> loader) {
        String suffix = collection.dbValue() + ':' + cityKey(citySlug) + ':' + limit;
        return readThrough(COLLECTION_PREFIX + suffix, LOCK_PREFIX + suffix, loader);
    }

    private AttractionPage readThrough(String dataKey, String lockKey, Supplier<AttractionPage> loader) {
        AttractionPage cached = read(dataKey);
        if (cached != null) {
            return cached;
        }

        String token = UUID.randomUUID().toString();
        Boolean locked = tryLock(lockKey, token);
        if (locked == null) {
            // 缓存整体不可用：等待也只会重复失败，直接查库
            return loader.get();
        }
        if (!locked) {
            AttractionPage waited = awaitCache(dataKey);
            return waited != null ? waited : loader.get();
        }

        try {
            AttractionPage loaded = loader.get();
            write(dataKey, loaded);
            return loaded;
        } finally {
            release(lockKey, token);
        }
    }

    /** 返回 null 表示未命中；缓存不可用或内容无法解析时同样返回 null，并已记 WARN。 */
    private AttractionPage read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return null;
            }
            return objectMapper.readValue(json, AttractionPage.class);
        } catch (JsonProcessingException e) {
            // 坏值不会自己好起来：删掉它，避免每次都撞同一份坏数据
            log.warn("attraction cache content unreadable, dropping key={}, cause={}",
                    key, e.getClass().getSimpleName());
            deleteQuietly(key);
            return null;
        } catch (RuntimeException e) {
            log.warn("attraction cache read failed, key={}, cause={}", key, e.getClass().getSimpleName());
            return null;
        }
    }

    private void write(String key, AttractionPage page) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(page), properties.ttl());
        } catch (JsonProcessingException e) {
            log.warn("attraction cache content unwritable, key={}, cause={}", key, e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            log.warn("attraction cache write failed, key={}, cause={}", key, e.getClass().getSimpleName());
        }
    }

    /** 抢到返回 true、被别人抢到返回 false、缓存不可用返回 null。 */
    private Boolean tryLock(String lockKey, String token) {
        try {
            return redis.opsForValue().setIfAbsent(lockKey, token, properties.lockTtl());
        } catch (RuntimeException e) {
            log.warn("attraction cache lock failed, key={}, cause={}", lockKey, e.getClass().getSimpleName());
            return null;
        }
    }

    private AttractionPage awaitCache(String dataKey) {
        long deadline = System.nanoTime() + properties.waitTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            AttractionPage cached = read(dataKey);
            if (cached != null) {
                return cached;
            }
        }
        return null;
    }

    private void release(String lockKey, String token) {
        try {
            redis.execute(RELEASE_IF_OWNED, List.of(lockKey), token);
        } catch (RuntimeException e) {
            // 锁会自己按 lockTtl 过期，释放失败不影响正确性，只影响下一个请求的等待
            log.warn("attraction cache lock release failed, key={}, cause={}", lockKey, e.getClass().getSimpleName());
        }
    }

    private void deleteQuietly(String key) {
        try {
            redis.delete(key);
        } catch (RuntimeException e) {
            log.warn("attraction cache delete failed, key={}, cause={}", key, e.getClass().getSimpleName());
        }
    }

    private static String cityKey(String citySlug) {
        return citySlug == null ? ALL_CITIES : citySlug;
    }
}
