package com.demo.supplyagent.mapper;

import com.demo.supplyagent.dto.ProductBrief;
import com.demo.supplyagent.dto.SalesDailyRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 销量查询专用 Mapper（sale_order 只读聚合 + product 摘要），供 query-sales 使用。
 */
public interface SalesQueryMapper {

    /**
     * sale_order 按 sku + order_date 聚合销量，只取 [start, end] 闭区间（含两端）。
     * 列别名与 DTO 字段名严格一致（MyBatis 别名对不上属性会静默丢字段）。
     */
    @Select("""
            <script>
            SELECT s.sku_code AS sku,
                   DATE_FORMAT(s.order_date, '%Y-%m-%d') AS orderDate,
                   SUM(s.qty) AS qty
            FROM sale_order s
            WHERE s.sku_code IN
             <foreach collection="skus" item="sku" open="(" separator="," close=")">#{sku}</foreach>
              AND s.order_date BETWEEN #{start} AND #{end}
            GROUP BY s.sku_code, s.order_date
            </script>
            """)
    List<SalesDailyRow> selectDailyTotals(@Param("skus") Collection<String> skus,
                                          @Param("start") LocalDate start,
                                          @Param("end") LocalDate end);

    /** 单个 SKU 主档摘要；不存在返回 null（调用方据此判 missing）。 */
    @Select("""
            SELECT sku_code AS sku,
                   name     AS productName,
                   category AS category
            FROM product
            WHERE sku_code = #{sku}
            """)
    ProductBrief selectProductBriefBySku(@Param("sku") String sku);

    /** 类目下全部 SKU 主档摘要（sku_code 排序，保证响应条目顺序稳定）。 */
    @Select("""
            SELECT sku_code AS sku,
                   name     AS productName,
                   category AS category
            FROM product
            WHERE category = #{category}
            ORDER BY sku_code
            """)
    List<ProductBrief> selectCategoryProductBriefs(@Param("category") String category);
}
