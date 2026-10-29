package com.luyi.myfirst.dao.post.repository;

import com.luyi.myfirst.dao.post.entity.Bookmark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 收藏查询入口。
 *
 * 所有读方法默认过滤已软删除记录。
 */
public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    Optional<Bookmark> findByPostIdAndAuthorIdAndDeletedAtIsNull(Long postId, Long authorId);

    boolean existsByPostIdAndDeletedAtIsNull(Long postId);

    long countByPostIdAndDeletedAtIsNull(Long postId);
}
