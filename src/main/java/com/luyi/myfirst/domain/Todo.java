package com.luyi.myfirst.domain;

/**
 * 待办事项。使用 record 保证不可变，完成状态通过生成新实例表达，
 * 避免可变对象在多线程下被隐式共享。
 */
public record Todo(long id, String title, boolean done) {

    public Todo complete() {
        return new Todo(id, title, true);
    }
}
