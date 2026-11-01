package com.luyi.myfirst.service.attraction;

import com.luyi.myfirst.service.attraction.model.AttractionPage;
import com.luyi.myfirst.service.attraction.model.AttractionView;

/**
 * 景点的只读能力（spec「景点接口为公开只读」）。
 *
 * 没有写方法：本期没有管理后台，榜单与内容由种子与手工 `UPDATE` 维护，
 * 这里凭空长出一套 {@code publish} / {@code curate} 只会是没有调用方的死路径。
 *
 * 城市、排序、榜单与条数的归一化全部发生在实现里（{@code AttractionQuery}），
 * 调用方传原始字符串即可——控制器因此不必知道白名单，也无法绕过它。
 */
public interface AttractionService {

    /**
     * 列表或榜单：只含已发布、未删除、已核实的景点。
     *
     * {@code collection} 为 null / 空白时是完整列表；取其白名单外的值抛
     * {@code ATTRACTION_COLLECTION_INVALID}（400）。{@code sortBy} 非法时回退 `hot`，
     * {@code limit} 超过 50 时钳制到 50。
     */
    AttractionPage list(String city, String sortBy, String collection, Integer limit);

    /**
     * 详情：未知、已删除、草稿、未核实的 {@code publicId} 一律
     * {@code ATTRACTION_NOT_FOUND}（404），不区分「不存在」与「存在但不可见」。
     */
    AttractionView read(String publicId);
}