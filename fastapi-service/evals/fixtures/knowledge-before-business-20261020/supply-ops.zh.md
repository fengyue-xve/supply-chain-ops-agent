# 供应链运营知识

本文档是供应链运营助手的业务规则参考资料，用于解释口径与字段语义；其中不含任何实时数据。

## [agent-data-boundary] 实时数据边界

本文档只解释规则口径（档位含义、计算公式、字段语义），不包含任何实时数据。

当前库存、销量、补货建议、采购单状态等实时数值，必须通过工具查询获得，不得依据本文档编造具体数值。

当工具返回结果与本文档的解释冲突时，以工具返回的数据为准。

## [stock-level] 库存档位

库存档位按库存快照中的可售数量 availableQty 计算，共四档，字段名为 stockLevel：

- out：availableQty 小于等于 0，缺货；
- low：availableQty 大于 0 但小于 20，偏低；
- ok：availableQty 在 20 到 200 之间（含两端），正常；
- high：availableQty 大于 200，偏高。

档位阈值当前为固定值，尚未支持按运营策略配置。

## [stock-snapshot] 库存快照与数据时点

query-stock-level 返回的是最新库存快照，不是逐笔扣减的实时流水。

- availableQty 是该快照日期的可售数量；snapDate 标识快照的业务日期。
- 本文档不包含任何具体日期：库存数据具有快照时点，具体日期必须以 Java 工具接口给出的 snapDate 字段为准，不得依据本文档回答当前库存的日期。
- 回答数据问题时，应同时说明数据时点（工具给出的快照日期或销量窗口区间），不要把快照数据表述为「此刻实时」。
- 查询没有返回某 SKU 时，无法据此判断该 SKU 的真实库存，只能说明未查到。

## [sales-window] 销量统计口径

query-sales 的统计窗口由 days 参数决定，days 只支持 7 或 30，且必须显式给出。

- 窗口范围：从基准日往前共 days 个自然日。基准日是最近一次业务数据日，不随系统时钟实时变化；具体日期以工具查询结果里的 series 起止日期为准，本文档不给出具体日期。
- series 按自然日逐日给出窗口内每一天的销量；当天没有销量时补 0，不会缺日。
- totalQty 是窗口内销量合计。
- avgDailyQty 是日均销量，等于 totalQty 除以 days，四舍五入保留两位小数；它是按窗口天数平均，不是按有销量的天数平均。

## [replenish-formula] 补货建议公式

补货建议数量 suggestedQty 的计算方式：

suggestedQty = 日均销量 × 目标覆盖天数 + 安全库存 − 当前库存，向上取整；若结果小于等于 0，则建议量为 0。

其中安全库存是固定值 20；日均销量取自销量查询（days 窗口口径）；当前库存取自库存快照。补货建议不写库、不生成采购单，只是计算输出。

## [replenish-urgency] 补货紧急度

补货结果的 urgency 字段共四档，判断顺序固定：

- out：当前库存 currentQty 小于等于 0，优先补货；
- urgent：建议量 suggestedQty 大于「日均销量 × 7」，即一次补货量超过一周销量，视为偏高需要关注；
- normal：建议量大于 0 且未达到 urgent；
- none：建议量为 0，按目标覆盖天数无需补货。

## [replenish-params] 补货与销量参数

- query-sales 与 suggest-replenish 的 days 参数只支持 7 或 30，二者都必须显式给出，没有缺省值。
- suggest-replenish 的 targetCoverDays（目标覆盖天数）支持 7、14、30，缺省是 14。
- 一次查询的 skus 列表最多 20 个；skus 与 category 只能二选一。

## [missing-skus] missingSkus 语义

请求的部分 SKU 在商品主档或快照中不存在时，它们不会出现在 items 中，而是列入 missingSkus 数组。

- 重复请求同一个 SKU 只计一次。
- items 与 missingSkus 可以同时非空，表示部分命中。
- 按类目查询时，如果该类目下没有商品数据，返回空结果（items 为空、total 为 0），这不是错误。
- 回答时应如实说明未找到的 SKU，不要用其他商品的数据替代，也不要猜测原因。

## [categories] 商品类目

系统当前覆盖三个商品类目：audio（音频）、charging（充电）、accessory（配件）。

库存、销量、补货查询既支持按 SKU 精确查询，也支持按类目查询，两种方式二选一。类目取值超出以上三类时，查询结果为空，应提示用户核对类目。

## [purchase-approval] 采购单与人工确认

采购相关写操作有两个：create-purchase-order 创建采购单，confirm-po 对采购单执行确认。

- 创建采购单会生成待人工确认的单据，不会直接生效。
- 确认动作必须由人工完成；助手不会代替人工确认，也不得声称已完成确认。
- 对话助手当前只做只读查询；涉及创建或确认采购单时，应说明需要人工操作并给出后续步骤建议。

## [movement-explanation] 库存异动解释边界

解释库存或销量异动时，只能基于工具返回的数据：

- 可用依据：query-sales 的逐日 series、totalQty、avgDailyQty，以及 query-stock-level 的快照与档位。
- 不得编造数据中不存在的原因（如促销活动、缺货、退货、物流延误等）。
- 现有数据不足以解释时应直接说明无法判断，并建议补充查询，例如扩大销量窗口、核对 SKU 编码或改按类目查看。

## [clarify-missing-input] 缺少 SKU 或类目时的追问

- 用户既没有给出 SKU 也没有给出类目时，应先追问，请用户提供 SKU 或类目，不要猜测。
- 不知道 SKU 时，可以引导用户改用类目查询；不确定类目时，说明当前支持 audio、charging、accessory 三类。
- 用户给出的 SKU 出现在 missingSkus 时，如实告知未找到，并请用户核对编码。
- days 等必填参数缺失时，向用户确认窗口口径（7 天或 30 天），不要静默改口径。
