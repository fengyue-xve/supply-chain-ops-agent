package com.demo.supplyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.supplyagent.entity.PoConfirmation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 确认流（po_confirmation）。所有状态迁移都是条件更新：
 * WHERE status='pending_confirmation' 保证并发下只有一个事务能迁移成功，
 * 受影响行数 = 0 的事务不做任何写入（回查后给 409）。
 */
public interface PoConfirmationMapper extends BaseMapper<PoConfirmation> {

    @Select("SELECT * FROM po_confirmation WHERE po_no = #{poNo}")
    PoConfirmation selectByPoNo(@Param("poNo") String poNo);

    @Update("UPDATE po_confirmation SET status = 'confirmed', updated_at = #{now} "
            + "WHERE po_no = #{poNo} AND status = 'pending_confirmation'")
    int confirmTransition(@Param("poNo") String poNo, @Param("now") LocalDateTime now);

    @Update("UPDATE po_confirmation SET status = 'failed', reason = #{reason}, updated_at = #{now} "
            + "WHERE po_no = #{poNo} AND status = 'pending_confirmation'")
    int failTransition(@Param("poNo") String poNo, @Param("reason") String reason,
                       @Param("now") LocalDateTime now);
}
