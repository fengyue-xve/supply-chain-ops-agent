package com.demo.supplyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.supplyagent.entity.IdempotencyRecord;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 请求级幂等登记。只有 insert（BaseMapper）与按作用域回查两条路径，无 UPDATE：
 * 唯一键 (tool_name, operator, idempotency_key) 冲突在事务内冒泡 → 事务回滚 →
 * 调用方在事务外按同作用域回查本表决定回放/409（绝不跨操作者回放他人结果）。
 */
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecord> {

    @Select("SELECT * FROM idempotency_record "
            + "WHERE tool_name = #{toolName} AND operator = #{operator} AND idempotency_key = #{key}")
    IdempotencyRecord selectByScope(@Param("toolName") String toolName,
                                    @Param("operator") String operator,
                                    @Param("key") String key);
}
