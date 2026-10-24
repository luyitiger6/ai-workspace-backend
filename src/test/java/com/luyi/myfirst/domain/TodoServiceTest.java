package com.luyi.myfirst.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TodoServiceTest {

    private final TodoService service = new TodoService();

    @Test
    @DisplayName("创建待办：返回未完成状态且标题去除首尾空格")
    void create_trimsTitleAndStartsUndone() {
        Todo created = service.create("  写第一个规格  ");

        assertThat(created.title()).isEqualTo("写第一个规格");
        assertThat(created.done()).isFalse();
        assertThat(created.id()).isPositive();
    }

    @Test
    @DisplayName("创建待办：空标题必须被拒绝")
    void create_rejectsBlankTitle() {
        assertThatThrownBy(() -> service.create("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    @DisplayName("完成待办：状态翻转为已完成且原记录保留")
    void complete_flipsDoneFlag() {
        Todo created = service.create("跑通 Harness");

        Todo completed = service.complete(created.id());

        assertThat(completed.done()).isTrue();
        assertThat(completed.id()).isEqualTo(created.id());
        assertThat(service.list()).hasSize(1);
    }

    @Test
    @DisplayName("完成待办：不存在的 id 必须报错")
    void complete_rejectsUnknownId() {
        assertThatThrownBy(() -> service.complete(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("列表：按创建顺序返回全部记录")
    void list_returnsInCreationOrder() {
        service.create("A");
        service.create("B");

        List<Todo> todos = service.list();

        assertThat(todos).extracting(Todo::title).containsExactly("A", "B");
    }
}
