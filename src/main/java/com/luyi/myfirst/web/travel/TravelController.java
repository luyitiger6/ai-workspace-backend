package com.luyi.myfirst.web.travel;

import com.luyi.myfirst.service.travel.CurrencyService;
import com.luyi.myfirst.service.travel.WeatherService;
import com.luyi.myfirst.service.travel.model.CurrencyTable;
import com.luyi.myfirst.service.travel.model.WeatherReading;
import com.luyi.myfirst.web.travel.dto.CurrencyRateResponse;
import com.luyi.myfirst.web.travel.dto.CurrencyResponse;
import com.luyi.myfirst.web.travel.dto.WeatherResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旅行工具的两个公开 GET（design D7）。
 *
 * 控制器只做三件事：收参数、service 模型转 DTO、返回 200。城市白名单、默认值、
 * 缓存与错误归一都在 service 与 provider 里，这里既不重复校验也不提供默认值——
 * 两处各写一份，迟早会分叉。
 *
 * 不取登录态：两个端点都是公开只读的，游客与登录用户看到的内容完全一致。
 *
 * 失败由此层之上的 {@code GlobalExceptionHandler} 翻译：非法城市 → 400，
 * 上游不可用 → 502，错误体与既有接口同形（{@code errorCode} + {@code message}）。
 */
@RestController
@RequestMapping("/api/travel")
public class TravelController {

    private final WeatherService weatherService;
    private final CurrencyService currencyService;

    public TravelController(WeatherService weatherService, CurrencyService currencyService) {
        this.weatherService = weatherService;
        this.currencyService = currencyService;
    }

    /**
     * 当前天气。
     *
     * {@code city} 省略时按 beijing 处理（与前端首次打开默认一致），
     * 白名单之外的值由 service 拒绝并转成 400。
     */
    @GetMapping("/weather")
    ResponseEntity<WeatherResponse> weather(@RequestParam(required = false) String city) {
        return ResponseEntity.ok(toResponse(weatherService.read(city)));
    }

    /** 以 CNY 为基准的五个币种。 */
    @GetMapping("/currency")
    ResponseEntity<CurrencyResponse> currency() {
        return ResponseEntity.ok(toResponse(currencyService.read()));
    }

    private static WeatherResponse toResponse(WeatherReading reading) {
        return new WeatherResponse(
                reading.city(),
                reading.cityName(),
                reading.temperatureC(),
                reading.condition(),
                reading.windSpeedKph(),
                reading.observedAt());
    }

    private static CurrencyResponse toResponse(CurrencyTable table) {
        return new CurrencyResponse(
                table.base(),
                table.updatedAt(),
                table.rates().stream()
                        .map(quote -> new CurrencyRateResponse(quote.code(), quote.value()))
                        .toList());
    }
}
