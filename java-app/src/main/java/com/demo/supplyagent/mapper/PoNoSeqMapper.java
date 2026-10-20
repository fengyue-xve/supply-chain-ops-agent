package com.demo.supplyagent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 采购单号同日序号分配（po_no_seq）。单条原子语句完成「行不存在则创建、存在则自增 N」，
 * 通过 LAST_INSERT_ID(expr) 把本次分配到的最大序号留在连接会话上：
 *   - 新日首分配：VALUES 分支 LAST_INSERT_ID(N) → 拿回 N；
 *   - 已有行：ON DUPLICATE 分支 LAST_INSERT_ID(last_seq + N) → 拿回新 last_seq。
 * 必须在同一事务（同一连接）内先 allocate 再 selectLastInsertId，两者不可分离；
 * 序号随事务回滚（分配与业务写入同生共死），代价是计数器行锁持有到事务提交。
 */
public interface PoNoSeqMapper {

    @Insert("INSERT INTO po_no_seq (seq_date, last_seq, created_at, updated_at) "
            + "VALUES (#{seqDate}, LAST_INSERT_ID(#{count}), #{now}, #{now}) "
            + "ON DUPLICATE KEY UPDATE last_seq = LAST_INSERT_ID(last_seq + #{count}), updated_at = #{now}")
    int allocate(@Param("seqDate") LocalDate seqDate, @Param("count") int count,
                 @Param("now") LocalDateTime now);

    /** 与上一条 allocate 同连接紧邻调用：返回本次分配到的最大序号。 */
    @Select("SELECT LAST_INSERT_ID()")
    long selectLastInsertId();
}
