package com.demo.supplyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.supplyagent.dto.PoPendingItem;
import com.demo.supplyagent.entity.PurchasePo;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 采购单（purchase_po）。状态迁移只走条件更新（WHERE status='pending'）：
 * 受影响行数 = 0 表示状态已被并发改变，由调用方决定语义（回滚/500），绝不盲目 UPDATE。
 * W4 增加 admin 只读待确认列表查询；项目无分页插件，LIMIT/OFFSET 与 COUNT 手写（禁用 selectPage）。
 */
public interface PurchasePoMapper extends BaseMapper<PurchasePo> {

    @Update("UPDATE purchase_po SET status = 'confirmed', updated_at = #{now} "
            + "WHERE po_no = #{poNo} AND status = 'pending'")
    int confirmPending(@Param("poNo") String poNo, @Param("now") LocalDateTime now);

    @Update("UPDATE purchase_po SET status = 'cancelled', updated_at = #{now} "
            + "WHERE po_no = #{poNo} AND status = 'pending'")
    int cancelPending(@Param("poNo") String poNo, @Param("now") LocalDateTime now);

    /** 在商品行锁内读取当前已提交的未确认意向；历史 confirmed 不作为永久禁购。 */
    @Select("SELECT * FROM purchase_po WHERE sku_code = #{sku} AND status = 'pending' "
            + "ORDER BY id FOR UPDATE")
    List<PurchasePo> selectPendingBySkuForUpdate(@Param("sku") String sku);

    /** 待确认过滤条件：page 与 count 共用同一常量，保证 count 与结果行同口径。 */
    String PENDING_FILTER = """
            WHERE status = 'pending'
            """;

    /**
     * 待确认采购单页：仅 pending 行，固定排序 created_at DESC, id DESC；
     * created_at 在 SQL 内格式化，避免为时间展示引入新的 JSON 序列化口径。
     */
    @Select("""
            SELECT po_no                   AS poNo,
                   sku_code                AS skuCode,
                   qty,
                   status,
                   manual_confirm_required AS manualConfirmRequired,
                   DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS createdAt
            FROM purchase_po
            """ + PENDING_FILTER + """
            ORDER BY created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<PoPendingItem> selectPendingPage(@Param("limit") int limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM purchase_po " + PENDING_FILTER)
    long countPending();
}
