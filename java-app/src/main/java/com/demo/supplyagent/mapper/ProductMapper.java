package com.demo.supplyagent.mapper;

import com.demo.supplyagent.dto.ProductWriteInfo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 商品主档只读查询（写工具校验用）：存在性 + 上架状态 + 包装数量。
 */
public interface ProductMapper {

    @Select("""
            <script>
            SELECT sku_code AS sku, shelf_status AS shelfStatus, pkg_qty AS pkgQty
            FROM product
            WHERE sku_code IN
            <foreach collection="skus" item="sku" open="(" separator="," close=")">#{sku}</foreach>
            </script>
            """)
    List<ProductWriteInfo> selectWriteInfoBySkus(@Param("skus") Collection<String> skus);

    /** 创建采购意向前按 SKU 升序逐行锁定，跨幂等键与操作者共享同一互斥边界。 */
    @Select("SELECT sku_code AS sku, shelf_status AS shelfStatus, pkg_qty AS pkgQty "
            + "FROM product WHERE sku_code = #{sku} FOR UPDATE")
    ProductWriteInfo selectWriteInfoForUpdate(@Param("sku") String sku);

    @Select("SELECT sku_code AS sku, shelf_status AS shelfStatus, pkg_qty AS pkgQty "
            + "FROM product WHERE sku_code = #{sku}")
    ProductWriteInfo selectWriteInfoBySku(@Param("sku") String sku);
}
