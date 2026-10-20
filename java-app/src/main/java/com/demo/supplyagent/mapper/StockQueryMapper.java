package com.demo.supplyagent.mapper;

import com.demo.supplyagent.dto.StockLevelItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 库存水位查询专用 Mapper（inventory_snapshot + product 只读），供 query-stock-level 使用。
 */
public interface StockQueryMapper {

    /**
     * 每个 SKU 只取最新 snap_date 那一行（子查询 MAX(snap_date) 派生表），
     * 与 product 主档 join 出名称/类目。结果行由 MyBatis 下划线转驼峰映射到 StockLevelItem。
     */
    @Select("""
            <script>
            SELECT p.sku_code AS sku,
                   p.name     AS productName,
                   p.category AS category,
                   p.safety_stock AS safetyQty,
                   p.lead_days AS leadDays,
                   p.pkg_qty AS pkgQty,
                   p.shelf_status AS shelfStatus,
                   s.stock_qty AS availableQty,
                   DATE_FORMAT(s.snap_date, '%Y-%m-%d') AS snapDate
            FROM inventory_snapshot s
            JOIN product p ON p.sku_code = s.sku_code
            JOIN (SELECT sku_code, MAX(snap_date) AS max_date
                    FROM inventory_snapshot
                   WHERE sku_code IN
                    <foreach collection="skus" item="sku" open="(" separator="," close=")">#{sku}</foreach>
                   GROUP BY sku_code) m
              ON m.sku_code = s.sku_code
             AND m.max_date = s.snap_date
            ORDER BY p.sku_code
            </script>
            """)
    List<StockLevelItem> selectLatestBySkus(@Param("skus") Collection<String> skus);

    @Select("SELECT sku_code FROM product WHERE category = #{category} ORDER BY sku_code")
    List<String> selectSkuCodesByCategory(@Param("category") String category);
}
