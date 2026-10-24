package com.luyi.myfirst.domain;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版待办服务。刻意不接数据库：Harness 的核心是「可重复」，
 * 外部依赖越少，验证回路的抖动就越小。
 */
@Service
public class TodoService {

    private final Map<Long, Todo> store = new LinkedHashMap<>();
    private long sequence = 0;

    public Todo create(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        long id = ++sequence;
        Todo todo = new Todo(id, title.trim(), false);
        store.put(id, todo);
        return todo;
    }

    public Todo complete(long id) {
        Todo existing = store.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("todo not found: " + id);
        }
        Todo updated = existing.complete();
        store.put(id, updated);
        return updated;
    }

    public List<Todo> list() {
        return new ArrayList<>(store.values());
    }
}
