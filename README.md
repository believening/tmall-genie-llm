# tmall-genie-llm —— 天猫精灵 × 大模型（海外部署版，provider 无关）

把天猫精灵的语音问答代理到 OpenAI 兼容的大模型 API（默认 OpenCode Zen 免费模型）。
部署在海外节点（Claw Cloud Run 日本区），一举解决两件事：

1. Zen 免费模型不对大陆 IP 开放 → 海外节点出口 IP 直接满足；
2. 阿里技能云需要公网 HTTPS 回调 → Claw 自动提供域名和证书。

链路：`音箱 → 阿里技能云(杭州) → Claw(日本) → OpenCode Zen → 原路返回播报`

## 环境变量（在 Claw 的 App 配置里填）

| 变量 | 必填 | 说明 |
|------|------|------|
| `LLM_API_KEY` | 是 | opencode.ai/auth 生成的 API Key |
| `LLM_API_BASE` | 否 | 默认 `https://opencode.ai/zen/v1/chat/completions` |
| `LLM_MODEL` | 否 | 默认 `deepseek-v4-flash-free`；备选 `mimo-v2.6-flash-free`、`ling-3.1-flash-free`、`nemotron-3.5-lightning-free` |
| `TAVILY_API_KEY` | 建议填 | tavily.com 免费注册（1000 次/月，无需信用卡）。不配则无联网搜索能力 |
| `GENIE_PUSH_*` 六件套 | task 功能必需 | 消息推送凭证：`ACCESS_KEY` / `ACCESS_SECRET` / `TEMPLATE_ID` / `SKILL_ID` / `ORG_ID` / `TARGET_ID`（含义见 PushNotifier.java 头注释）。配齐后异步研究才会启用 |

## 联网搜索是怎么工作的

采用"三段式"：第一跳模型能自信回答就直接出答案（闲聊零额外延迟）；
问题涉及时效信息（天气/新闻/股价/比分/新版本）时模型输出 `SEARCH: 关键词`，
后端调 Tavily 取前 5 条结果，第二跳带着结果生成口语化短答案并播报。
搜过的问题走两跳 + 一次搜索，总延迟约 3-6 秒。

## 一、构建镜像（GitHub Actions 自动）

1. 在 GitHub 建一个仓库（public 最省事；private 也行，但要把推送出的
   package 设为 public：仓库页 → Packages → Package settings → Change visibility）；
2. 把本目录整个推上去（`.github/workflows/docker.yml` 会自动构建并推到 GHCR）：
   ```
   git init && git add . && git commit -m init
   git branch -M main
   git remote add origin https://github.com/<你的用户名>/<仓库名>.git
   git push -u origin main
   ```
3. Actions 跑完后镜像地址为 `ghcr.io/<你的用户名>/<仓库名>:latest`。

## 二、部署到 Claw Cloud Run

1. 用 GitHub 账号（注册满 180 天）登录 claw.cloud，每月自动送 $5 额度；
2. App Launchpad → Create App：
   - Image：`ghcr.io/<你的用户名>/<仓库名>:latest`
   - Region：`Asia-Pacific (Japan)` 或 Singapore
   - 规格：0.1 CPU / 256MB（月成本约 $2-3，免费额度内）
   - Port：8080（Network 开启 Public Access）
   - Environment：填上面的 `LLM_API_KEY` 等
3. 建好后会得到形如 `https://xxx-xxxx.claw.cloud` 的公网地址；
4. 浏览器自检：`curl https://xxx.claw.cloud/` 返回报错 JSON（400/405）也算通——
   说明服务活着，等着 POST。

## 三、配置天猫精灵技能

1. iap.aligenie.com → 创建自定义技能（私域）→ 调用词如"电脑助手"；
2. 语音交互模型：意图 `chat` + 参数 `anyText`（**任意文本类型**）+ 示例语料；
   再按同样方式建一个 `task` 意图（也是 `anyText` 参数），承接深度研究类请求；
3. 服务部署：选**自建服务**，地址填 `https://xxx.claw.cloud/`；
   - 平台会校验域名归属：按提示下载校验文件，放进本项目的
     `src/main/resources/static/` 目录，push 重新部署后再点验证；
4. 在线模拟测试 chat 意图 → 真机："天猫精灵，电脑助手讲个笑话"。

## 异步深度研究（task 意图）

对音箱说"天猫精灵，电脑助手，帮我研究一下XX"，音箱**立即**回复
"好的，我去查资料了"，后台最多跑 3 轮"搜索→读结果→再搜索"，完成后通过
**消息推送**主动把总结念出来（通常 1-3 分钟）。

前置条件（在 iap.aligenie.com 上操作，需审核，可与主链路并行申请）：
1. 技能申请「消息推送（普通版）」能力；
2. 建一个消息模板（含一个占位符，如 `${content}`），等审核通过拿到模板ID；
3. 从技能后端日志抄下你的 openId/unionId；
4. 把 `GENIE_PUSH_*` 六个环境变量配到 Claw。

技能未正式发布期间，推送走调试模式（`GENIE_PUSH_IS_DEBUG` 默认 true）。

## 常见问题

| 现象 | 处理 |
|------|------|
| 平台说服务不可达 | Claw App 是否 Running；端口是否 8080；Public Access 是否开 |
| 回复"服务器还没配置大模型 API Key" | Claw 环境变量没生效，改完要 Restart |
| 回复"大模型接口出错了" | 看 Claw 日志 `[LLM ERR]` 行：一般是 Key 无效 / 模型名错 / 地区限制 |
| 回复"我这边网络开小差" | 看 `[LLM EX]`：超时则换个 flash 级免费模型 |
| 回复"还没配置搜索服务" | Claw 环境变量缺 `TAVILY_API_KEY`，补上并 Restart |
| 搜索类问题总是不触发搜索 | 看 `[SEARCH]` 日志有没有出现；没有则是模型没输出指令，换模型试试 |
| 意图不命中 | 补语料；确认 anyText 是"任意文本"类型 |
| 回复延迟 >5 秒 | 免费模型高峰期慢；换模型或错峰 |

## 备注

- 多轮记忆存进程内存，Claw 重启/换实例即清空，属正常现象；
- Zen 免费模型"免费期内对话数据可能用于模型改进"，勿对着音箱说隐私；
- Zen 账号默认开启"余额低于 $5 自动充 $20"，纯用免费模型前建议在
  opencode.ai 的 Billing 里关掉自动充值或设月度限额。
