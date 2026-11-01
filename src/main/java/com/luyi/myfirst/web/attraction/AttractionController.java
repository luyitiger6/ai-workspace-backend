package com.luyi.myfirst.web.attraction;

import com.luyi.myfirst.service.attraction.AttractionService;
import com.luyi.myfirst.service.attraction.model.AttractionPage;
import com.luyi.myfirst.service.attraction.model.AttractionSummary;
import com.luyi.myfirst.service.attraction.model.AttractionView;
import com.luyi.myfirst.web.attraction.dto.AttractionDetailResponse;
import com.luyi.myfirst.web.attraction.dto.AttractionListItemResponse;
import com.luyi.myfirst.web.attraction.dto.AttractionListResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 景点 REST 端点（design D1）：两个公开 GET，没有写端点。
 *
 * 控制器只做三件事：收原始查询参数、DTO 与 service 模型互转、返回 200。
 * 城市 / 排序 / 榜单 / 条数的白名单与钳制全在 {@code AttractionQuery} 里，
 * 这里既不重复校验也不提供默认值——两处各写一份，迟早会分叉。
 *
 * 不取 {@code @RequestAttribute(VERIFIED)}：这两个端点是公开只读的，
 * 拿不到登录态也不影响响应，因此没有「游客 vs 本人」的分支。
 */
@RestController
@RequestMapping("/api/attractions")
public class AttractionController {

    private final AttractionService attractionService;

    public AttractionController(AttractionService attractionService) {
        this.attractionService = attractionService;
    }

    /**
     * 列表或榜单。
     *
     * 四个参数都可选：省略 {@code collection} 是完整列表，省略 {@code city} 或取 `all`
     * 是全部城市，省略 {@code sortBy} 按浏览数降序，省略 {@code limit} 取 20。
     * {@code limit} 写成 {@code Integer} 而不是 {@code int}：非数字由框架按参数错误
     * 处理，与 posts 的 {@code feed} 同一口径，这里不自己解析字符串。
     */
    @GetMapping
    ResponseEntity<AttractionListResponse> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String collection,
            @RequestParam(required = false) Integer limit) {
        AttractionPage page = attractionService.list(city, sortBy, collection, limit);
        return ResponseEntity.ok(new AttractionListResponse(
                page.items().stream().map(AttractionController::toListItem).toList(),
                page.total()));
    }

    /** 详情：未知 / 已删除 / 草稿 / 未核实一律 404（由服务层抛 {@code BizException}）。 */
    @GetMapping("/{publicId}")
    ResponseEntity<AttractionDetailResponse> read(@PathVariable String publicId) {
        return ResponseEntity.ok(toDetail(attractionService.read(publicId)));
    }

    private static AttractionListItemResponse toListItem(AttractionSummary summary) {
        return new AttractionListItemResponse(
                summary.publicId(),
                summary.nameEn(),
                summary.nameCn(),
                summary.city(),
                summary.coverUrl(),
                summary.rating(),
                summary.viewCount(),
                summary.publishedAt(),
                summary.freeEntry(),
                summary.bookingRequired(),
                summary.openOnMonday());
    }

    private static AttractionDetailResponse toDetail(AttractionView view) {
        return new AttractionDetailResponse(
                view.publicId(),
                view.nameEn(),
                view.nameCn(),
                view.city(),
                view.coverUrl(),
                view.rating(),
                view.viewCount(),
                view.publishedAt(),
                view.freeEntry(),
                view.bookingRequired(),
                view.openOnMonday(),
                view.summaryEn(),
                view.contentEn(),
                view.ticketInfoEn(),
                view.openingHoursEn(),
                view.bookingInfoEn(),
                view.paymentInfoEn(),
                view.addressEn(),
                view.addressCn(),
                view.transitEn(),
                view.suggestedDurationEn(),
                view.verifiedAt());
    }
}
