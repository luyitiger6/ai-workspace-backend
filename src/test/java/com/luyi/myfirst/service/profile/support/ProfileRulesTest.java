package com.luyi.myfirst.service.profile.support;

import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.service.profile.model.ProfileUpdate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 资料校验与标签清单的不变量（任务 1.3）。
 *
 * 清单非空、无重复、不含半角逗号、无首尾空白；四项门槛按 code point 计长。
 * 校验失败一律 VALIDATION_FAILED（400），与调用方身份无关。
 */
class ProfileRulesTest {

    @Test
    @DisplayName("标签清单：非空、无重复、不含半角逗号、无首尾空白")
    void catalogIsNonEmptyAndWellFormed() {
        assertThat(InterestCatalog.INTERESTS).isNotEmpty();
        assertThat(InterestCatalog.INTERESTS).doesNotHaveDuplicates();
        for (String each : InterestCatalog.INTERESTS) {
            assertThat(each).doesNotContain(",");
            assertThat(each).isEqualTo(each.strip());
        }
    }

    @Test
    @DisplayName("昵称 strip 后取值，空白被拒")
    void displayNameStripsAndRejectsBlank() {
        ProfileUpdate normalized = ProfileRules.normalize(new ProfileUpdate("  Alice  ", null, null, null));
        assertThat(normalized.displayName()).isEqualTo("Alice");

        expectValidationFailed(new ProfileUpdate("   ", null, null, null));
    }

    @Test
    @DisplayName("昵称按 code point 计长：64 个 emoji 通过，65 个被拒")
    void displayNameCountsCodePoints() {
        String sixtyFour = "😀".repeat(64);
        assertThat(ProfileRules.normalize(new ProfileUpdate(sixtyFour, null, null, null)).displayName())
                .isEqualTo(sixtyFour);

        expectValidationFailed(new ProfileUpdate("😀".repeat(65), null, null, null));
    }

    @Test
    @DisplayName("简介 ≤200：空串合法、超过 200 被拒")
    void bioIsBounded() {
        assertThat(ProfileRules.normalize(new ProfileUpdate(null, null, "", null)).bio()).isEmpty();
        assertThat(ProfileRules.normalize(new ProfileUpdate(null, null, null, null)).bio()).isNull();

        expectValidationFailed(new ProfileUpdate(null, null, "a".repeat(201), null));
    }

    @Test
    @DisplayName("头像：空、http(s) 绝对地址合法；其它协议与相对路径被拒")
    void avatarIsEitherEmptyOrHttpAbsolute() {
        assertThat(ProfileRules.normalize(new ProfileUpdate(null, "", null, null)).avatarUrl()).isEmpty();
        assertThat(ProfileRules.normalize(new ProfileUpdate(null, " https://cdn.example.com/a.png ", null, null)).avatarUrl())
                .isEqualTo("https://cdn.example.com/a.png");

        expectValidationFailed(new ProfileUpdate(null, "ftp://cdn.example.com/a.png", null, null));
        expectValidationFailed(new ProfileUpdate(null, "/images/a.png", null, null));
    }

    @Test
    @DisplayName("标签先去重再判上限：6 个重复值去重为 1 个通过")
    void interestsDedupBeforeLimit() {
        List<String> sixDuplicates = List.of("hiking", "hiking", "hiking", "hiking", "hiking", "hiking");
        ProfileUpdate normalized = ProfileRules.normalize(new ProfileUpdate(null, null, null, sixDuplicates));
        assertThat(normalized.interests()).containsExactly("hiking");
    }

    @Test
    @DisplayName("标签 6 个不同值、清单外值、超 16 个 code point 均被拒")
    void interestsRejectBadValues() {
        expectValidationFailed(new ProfileUpdate(null, null, null,
                List.of("hiking", "food", "photography", "camping", "cycling", "museums")));
        expectValidationFailed(new ProfileUpdate(null, null, null, List.of("not-in-catalog")));
        expectValidationFailed(new ProfileUpdate(null, null, null, List.of("a".repeat(17))));
    }

    @Test
    @DisplayName("标签空集合清空、null 保持原值")
    void interestsEmptyAndNull() {
        assertThat(ProfileRules.normalize(new ProfileUpdate(null, null, null, List.of())).interests()).isEmpty();
        assertThat(ProfileRules.normalize(new ProfileUpdate(null, null, null, null)).interests()).isNull();
    }

    private static void expectValidationFailed(ProfileUpdate raw) {
        try {
            ProfileRules.normalize(raw);
            fail("expected BizException with VALIDATION_FAILED");
        } catch (BizException ex) {
            assertThat(ex.getErrorCode()).isEqualTo("VALIDATION_FAILED");
        }
    }
}
