package com.luyi.myfirst.web.attraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luyi.myfirst.common.id.Ulid;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 景点端点的 HTTP 契约（spec「景点接口为公开只读」「列表项字段口径」「详情返回完整决策字段」）。
 *
 * 这里只钉三件 service 层表达不了的事：
 *
 * 1. **没有 cookie 也是 200**：公开只读不能靠「测试恰好带了令牌」蒙过去，
 *    所以每个用例都不带 cookie——这同时也验证了拦截器白名单登记生效（任务 2.6）。
 * 2. **字段集合**：JSON 的键名逐个比对，而不是只挑几个断言存在。
 * 3. **错误体形状**：404 与 400 都走统一 `{code, message}`。
 *
 * 详情用例直接打种子 {@code public_id}：种子住在 `schema.sql` 里、随闸门重建恢复，
 * 它比测试自己插的行更稳定（测试事务回滚后不会残留）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AttractionControllerTest {

    private static final String SEED_PUBLIC_ID = "01JQATTR000000000000000001";
    private static final String SEED_DRAFT_PUBLIC_ID = "01JQATTR000000000000000013";

    private static final List<String> LIST_FIELDS = List.of(
            "publicId", "nameEn", "nameCn", "city", "coverUrl", "rating", "viewCount",
            "publishedAt", "freeEntry", "bookingRequired", "openOnMonday");

    private static final List<String> DETAIL_ONLY_FIELDS = List.of(
            "summaryEn", "contentEn", "ticketInfoEn", "openingHoursEn", "bookingInfoEn",
            "paymentInfoEn", "addressEn", "addressCn", "transitEn", "suggestedDurationEn",
            "verifiedAt");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("列表未登录返回 200，条目字段恰好是 11 个列表字段")
    void listIsPublicAndReturnsOnlyListFields() throws Exception {
        JsonNode body = getJson("/api/attractions");

        assertThat(body.get("total").isNumber()).isTrue();
        JsonNode items = body.get("items");
        assertThat(items.isArray()).isTrue();
        assertThat(items.size()).isPositive();

        for (JsonNode item : items) {
            assertThat(fieldNames(item)).containsExactlyInAnyOrderElementsOf(LIST_FIELDS);
            assertThat(item.has("contentEn")).isFalse();
            assertThat(item.has("addressCn")).isFalse();
        }
    }

    @Test
    @DisplayName("列表支持 city 过滤与 collection 榜单，均无需登录")
    void listSupportsCityAndCollection() throws Exception {
        JsonNode byCity = getJson("/api/attractions?city=beijing");
        assertThat(byCity.get("items").size()).isPositive();
        for (JsonNode item : byCity.get("items")) {
            assertThat(item.get("city").asText()).isEqualTo("beijing");
        }

        JsonNode curated = getJson("/api/attractions?collection=no-booking");
        assertThat(curated.get("total").asLong()).isPositive();
        assertThat(curated.get("items").size()).isPositive();
    }

    @Test
    @DisplayName("详情未登录返回 200，字段恰好是列表 11 个 + 详情 11 个")
    void detailIsPublicAndReturnsFullFieldSet() throws Exception {
        JsonNode body = getJson("/api/attractions/" + SEED_PUBLIC_ID);

        assertThat(body.get("publicId").asText()).isEqualTo(SEED_PUBLIC_ID);
        assertThat(body.get("city").asText()).isEqualTo("beijing");
        assertThat(body.get("verifiedAt").isNull()).isFalse();
        assertThat(fieldNames(body)).containsExactlyInAnyOrderElementsOf(concat());
    }

    @Test
    @DisplayName("未知 publicId：404 与统一错误体 ATTRACTION_NOT_FOUND")
    void missingPublicIdReturns404() throws Exception {
        mockMvc.perform(get("/api/attractions/{publicId}", Ulid.generate()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTRACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    @DisplayName("草稿种子按不存在处理：404 且不泄漏任何字段")
    void draftSeedIsNotReadable() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/attractions/{publicId}", SEED_DRAFT_PUBLIC_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ATTRACTION_NOT_FOUND"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("Yu Garden");
    }

    @Test
    @DisplayName("collection 取白名单外：400 与 ATTRACTION_COLLECTION_INVALID，不返回任何景点")
    void invalidCollectionReturns400() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/attractions").param("collection", "hidden-gems"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ATTRACTION_COLLECTION_INVALID"))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"items\"");
    }

    @Test
    @DisplayName("limit 超过上限不报错：仍返回 200，条数不超过 50")
    void oversizedLimitIsClampedNotRejected() throws Exception {
        JsonNode body = getJson("/api/attractions?limit=999");

        assertThat(body.get("items").size()).isLessThanOrEqualTo(50);
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<String> concat() {
        List<String> all = new ArrayList<>(LIST_FIELDS);
        all.addAll(DETAIL_ONLY_FIELDS);
        return all;
    }
}
