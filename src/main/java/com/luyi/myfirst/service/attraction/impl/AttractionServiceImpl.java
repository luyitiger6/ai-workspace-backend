package com.luyi.myfirst.service.attraction.impl;

import com.luyi.myfirst.common.enums.AttractionStatus;
import com.luyi.myfirst.common.exception.AttractionErrorEnum;
import com.luyi.myfirst.common.exception.BizException;
import com.luyi.myfirst.dao.attraction.entity.Attraction;
import com.luyi.myfirst.dao.attraction.repository.AttractionRepository;
import com.luyi.myfirst.service.attraction.AttractionService;
import com.luyi.myfirst.service.attraction.model.AttractionPage;
import com.luyi.myfirst.service.attraction.model.AttractionSummary;
import com.luyi.myfirst.service.attraction.model.AttractionView;
import com.luyi.myfirst.service.attraction.support.AttractionQuery;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 景点服务实现。
 *
 * 列表与榜单走同一条出口、只在取数处分开：
 *
 * - **完整列表**：排序 + {@code limit} 交给查询，{@code total} 由同条件的 count 给出；
 * - **榜单**：查询返回该榜全量成员（顺序即策展顺序），服务层按 {@code limit} 截断，
 *   {@code total} 取截断前的成员数。
 *
 * 两者都不拿 {@code items.size()} 充 {@code total}，理由见 {@link AttractionPage}。
 *
 * 可见性不在这一层判断：{@code status = published}、{@code deleted_at IS NULL}、
 * {@code verified_at IS NOT NULL} 三条已写进仓储的每条查询，服务层只负责把
 * 「查不到」翻译成 404。
 */
@Service
public class AttractionServiceImpl implements AttractionService {

    private final AttractionRepository attractions;

    public AttractionServiceImpl(AttractionRepository attractions) {
        this.attractions = attractions;
    }

    @Override
    @Transactional(readOnly = true)
    public AttractionPage list(String city, String sortBy, String collection, Integer limit) {
        AttractionQuery query = AttractionQuery.parse(city, sortBy, collection, limit);
        if (query.collection() != null) {
            return curatedPage(query);
        }
        return listPage(query);
    }

    @Override
    @Transactional(readOnly = true)
    public AttractionView read(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            throw notFound();
        }
        return attractions
                .findByPublicIdAndStatusAndVerifiedAtIsNotNullAndDeletedAtIsNull(publicId, AttractionStatus.PUBLISHED)
                .map(AttractionServiceImpl::toView)
                .orElseThrow(AttractionServiceImpl::notFound);
    }

    private AttractionPage listPage(AttractionQuery query) {
        List<Attraction> rows = fetchSorted(query);
        List<AttractionSummary> items = rows.stream()
                .map(AttractionServiceImpl::toSummary)
                .toList();
        return new AttractionPage(items, countVisible(query));
    }

    /**
     * 榜单：先取全量成员再截断。
     *
     * 策展集合的量级由人工决定（本期个位数），先截断会丢掉真实成员数，
     * 让 {@code total} 恒等于 {@code limit}。
     */
    private AttractionPage curatedPage(AttractionQuery query) {
        List<Attraction> members = fetchCurated(query);
        List<AttractionSummary> items = members.stream()
                .limit(query.limit())
                .map(AttractionServiceImpl::toSummary)
                .toList();
        return new AttractionPage(items, members.size());
    }

    private List<Attraction> fetchSorted(AttractionQuery query) {
        Pageable pageable = PageRequest.of(0, query.limit());
        String city = query.citySlug();
        if (city == null) {
            return switch (query.sortBy()) {
                case HOT -> attractions.findHot(AttractionStatus.PUBLISHED, pageable);
                case RATING -> attractions.findRated(AttractionStatus.PUBLISHED, pageable);
                case NEW -> attractions.findNewest(AttractionStatus.PUBLISHED, pageable);
            };
        }
        return switch (query.sortBy()) {
            case HOT -> attractions.findHotByCity(AttractionStatus.PUBLISHED, city, pageable);
            case RATING -> attractions.findRatedByCity(AttractionStatus.PUBLISHED, city, pageable);
            case NEW -> attractions.findNewestByCity(AttractionStatus.PUBLISHED, city, pageable);
        };
    }

    private List<Attraction> fetchCurated(AttractionQuery query) {
        String city = query.citySlug();
        if (city == null) {
            return switch (query.collection()) {
                case FIRST_TRIP -> attractions.findCuratedFirstTrip(AttractionStatus.PUBLISHED);
                case NO_BOOKING -> attractions.findCuratedNoBooking(AttractionStatus.PUBLISHED);
                case OPEN_MONDAY -> attractions.findCuratedOpenMonday(AttractionStatus.PUBLISHED);
            };
        }
        return switch (query.collection()) {
            case FIRST_TRIP -> attractions.findCuratedFirstTripByCity(AttractionStatus.PUBLISHED, city);
            case NO_BOOKING -> attractions.findCuratedNoBookingByCity(AttractionStatus.PUBLISHED, city);
            case OPEN_MONDAY -> attractions.findCuratedOpenMondayByCity(AttractionStatus.PUBLISHED, city);
        };
    }

    private long countVisible(AttractionQuery query) {
        if (query.citySlug() == null) {
            return attractions.countVisible(AttractionStatus.PUBLISHED);
        }
        return attractions.countVisibleByCity(AttractionStatus.PUBLISHED, query.citySlug());
    }

    private static AttractionSummary toSummary(Attraction attraction) {
        return new AttractionSummary(
                attraction.publicId(),
                attraction.nameEn(),
                attraction.nameCn(),
                attraction.citySlug(),
                attraction.coverUrl(),
                attraction.rating(),
                attraction.viewCount(),
                attraction.publishedAt(),
                attraction.freeEntry(),
                attraction.bookingRequired(),
                attraction.openOnMonday());
    }

    private static AttractionView toView(Attraction attraction) {
        return new AttractionView(
                attraction.publicId(),
                attraction.nameEn(),
                attraction.nameCn(),
                attraction.citySlug(),
                attraction.coverUrl(),
                attraction.rating(),
                attraction.viewCount(),
                attraction.publishedAt(),
                attraction.freeEntry(),
                attraction.bookingRequired(),
                attraction.openOnMonday(),
                attraction.summaryEn(),
                attraction.contentEn(),
                attraction.ticketInfoEn(),
                attraction.openingHoursEn(),
                attraction.bookingInfoEn(),
                attraction.paymentInfoEn(),
                attraction.addressEn(),
                attraction.addressCn(),
                attraction.transitEn(),
                attraction.suggestedDurationEn(),
                attraction.verifiedAt());
    }

    private static BizException notFound() {
        return new BizException(AttractionErrorEnum.ATTRACTION_NOT_FOUND);
    }
}