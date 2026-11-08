# AI 预算、用量与外部数据处理记录

核对日期：2026-11-08。生产每日金额上限由作者确定为 **5 元**，不自动充值。以下是应用自身的保守估算与拦截，供应商账单仍以供应商记录为准。

## 价格快照与配置

| 服务 | 模型/区域 | 输入元/百万 token | 输出元/百万 token |
|---|---|---:|---:|
| DeepSeek | `deepseek-flash`，官方中国接口，高峰、缓存未命中价格 | 2 | 8 |
| 阿里云百炼 | `qwen3.7-text-embedding-flash`，华北2北京，同步接口 | 0.125 | 不计费 |

查询依据：[DeepSeek 官方人民币价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)、[百炼模型价格](https://help.aliyun.com/zh/model-studio/model-pricing)。使用较高的 Chat 单价，不扣除缓存、免费额度和优惠；不换算美元。模型别名对应的服务版本可能更新，价格或模型改变时须复核这份快照及配置。

显式环境变量：`AI_COST_ENABLED=true`、`AI_DAILY_COST_CAP=5`、`AI_CHAT_INPUT_CNY_PER_MILLION=2`、`AI_CHAT_OUTPUT_CNY_PER_MILLION=8`、`AI_EMBEDDING_INPUT_CNY_PER_MILLION=0.125`、`AI_COST_CHAT_MODEL=deepseek-flash`、`AI_COST_EMBEDDING_MODEL=qwen3.7-text-embedding-flash`、`AI_COST_PRICE_DATE=2026-11-08`。Key 仍只从原受保护后端环境读取。费率缺失、无效或模型与快照不匹配时拒绝付费调用，核心业务仍可运行。

## 拦截和结算语义

- 每次 Chat、重试、Agent 模型轮次、查询或建库 Embedding 都在供应商 I/O 前原子预留。计数配额、并发限制与金额限制同时存在。
- 金额以整数微元存储在 Redis，按 `Asia/Shanghai` 日期累计。同一 Redis 的多个应用实例共享预算；Lua 在一个原子操作内检查并增加余额。
- 输入按 UTF-8 字节数加协议余量保守预留，Chat 包含消息历史及工具 schema，输出按 `maxOutputTokens` 预留；这不是精确 tokenizer，也不是供应商计费凭证。
- 请求完整成功且供应商返回完整 usage 时，使用输入/输出 token 与配置的高价估算结算。流式协议显式请求 usage，保留每次调用的可得值。
- 缺失 usage 写 `null`。兼容 Spring AI 把缺失字段归零的情况，优先核对 native usage 的原始可空字段；缺少输出用量时不按0结算。取消、错误、超时、缺失 usage 不自动释放预留金额。失败重试单独预留，不用“失败即免费”绕过预算。
- Redis 不可用时拒绝新的模型请求；结算存储失败时保留已预留金额。达到限额返回安全的 429（已建流则 SSE error），不自动充值或扩大配额。
- 限额只覆盖本应用、当前这套 Redis 账本及当前费率；其他程序使用同一 Key、供应商涨价或账单差异不受此账本控制。供应商侧硬额度/自动充值设置未通过本机 API 代改。

用量日志仅记录 requestId、操作类型、模型名、结果、耗时、可得 token、预留和估算金额，不记录正文、问题、答案、Token、Key 或专属接口地址。最近 128 个请求的内存诊断快照只保留这些无正文元数据，不是对话记忆，不对外提供查询接口。评测文件按请求保存同样信息，以便核对费用与失败。

## 账户和保留策略

2026-11-08 使用 [DeepSeek 官方余额接口](https://api-docs.deepseek.com/zh-cn/api/get-user-balance/)进行只读核对，账户 `is_available=true`、余额币种 CNY；不公开余额数值，也未充值。百炼 OpenAI-compatible Key 的调用能力通过真实 Embedding 评测验证；该 Key 不提供阿里云账户财务管理权限，因此不声称读到了账户余额或已配置供应商财务硬限额。

[DeepSeek 隐私政策](https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html)说明其适用范围包含 API，并描述输入输出可能用于模型与服务优化，存在法定留存例外；未核实到本账户享有独立的零保留协议，不能宣称“外部服务不存储/不训练”。摘要入口因此明确提示正文将发给外部模型，不输入密钥或个人敏感信息。知识库仅发送允许公开的文章/项目片段，助手工具也只返回公开内容。

[百炼隐私说明](https://help.aliyun.com/zh/model-studio/privacy-notice)表示不将客户数据用于模型训练，同时依法存储模型/应用调用产生的数据。[监控告警说明](https://help.aliyun.com/zh/model-studio/model-telemetry/)区分默认审计日志与需手动开启的完整推理日志；不能由默认审计日志不含正文推断为全服务零保留。未操作账户控制台开启推理日志或日志投递；账户已有设置未通过模型 Key 越权推断。

本轮只按公开政策和实际可得权限记录事实，不假设供应商账户的私有配置。若以后接入私密知识库或真实用户个人信息，须重新核对数据处理范围；本轮保持原计划的公开内容边界。
