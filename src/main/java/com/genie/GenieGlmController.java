package com.genie;

import com.alibaba.da.coin.ide.spi.standard.ResultModel;
import com.alibaba.da.coin.ide.spi.standard.TaskQuery;
import com.alibaba.da.coin.ide.spi.standard.TaskResult;
import com.alibaba.da.coin.ide.spi.trans.MetaFormat;
import com.alibaba.da.coin.ide.spi.meta.ExecuteCode;
import com.alibaba.da.coin.ide.spi.meta.ResultType;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 天猫精灵 × 大模型（OpenAI 兼容 API）+ 必要时联网搜索 —— 海外自建服务形态。
 *
 * 工作流（问答三段式，闲聊不增加延迟）：
 *   1. 第一次调模型：能自信回答就直接给答案；需要实时信息时模型只回
 *      "SEARCH: 关键词"；
 *   2. 命中 SEARCH → 调 Tavily 搜索（免费 1000 次/月）取前 5 条结果；
 *   3. 第二次调模型：带着搜索结果生成口语化短答案。
 *
 * 环境变量：
 *   LLM_API_KEY   = OpenCode Zen 的 API Key（opencode.ai/auth）
 *   LLM_API_BASE  = 默认 https://opencode.ai/zen/v1/chat/completions
 *   LLM_MODEL     = 默认 deepseek-v4-flash-free（免费，支持中文，响应快）
 *   TAVILY_API_KEY= tavily.com 注册后的搜索 Key（免费 1000 次/月，不配则无搜索能力）
 *
 * 平台侧（iap.aligenie.com）语音交互模型需先配好：
 *   意图 chat + 参数 anyText（任意文本类型）+ 示例语料。
 */
@RestController
public class GenieGlmController {

    private static final Logger log = LoggerFactory.getLogger(GenieGlmController.class);

    private static final String DEFAULT_API_BASE =
            "https://opencode.ai/zen/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-v4-flash-free";
    private static final String TAVILY_URL = "https://api.tavily.com/search";

    /** 意图里"任意文本"参数名，需与平台配置一致 */
    private static final String SLOT_NAME = "anyText";
    private static final int MAX_REPLY_LEN = 200;
    private static final int HISTORY_LIMIT = 20;

    /** 第一跳：直接回答，或输出 SEARCH 指令 */
    private static final String SYSTEM_PROMPT =
            "你是运行在天猫精灵音箱上的语音助手。回答必须口语化、简洁，单次不超过100字，"
            + "不要使用任何Markdown格式、列表或表情符号，因为回复会被直接朗读出来。"
            + "判断规则：如果凭已有知识能自信回答（闲聊、常识、创作、推理、计算等），"
            + "直接给出回答；只有当问题依赖实时或最新的网络信息（例如今天或最近的天气、"
            + "新闻、股价汇率、体育比分、软件最新版本、近期发生的事件），或者你不确定"
            + "答案的时效性时，才只输出一行，格式严格为：SEARCH: 搜索关键词。"
            + "输出SEARCH时不要带任何其他内容，搜索关键词要精炼（不超过15个字）。";

    /** 第二跳：基于搜索结果作答 */
    private static final String SYSTEM_SEARCH_ANSWER =
            "你是运行在天猫精灵音箱上的语音助手。请只根据用户问题后面附带的网络搜索结果作答："
            + "口语化、简洁、不超过100字、无Markdown格式。开头可以加一句\"根据网上的信息\"。"
            + "如果搜索结果不足以回答问题，就直说没查到，并建议用户换个问法，不要编造。";

    private static final Pattern SEARCH_PREFIX =
            Pattern.compile("(?is)^\\s*SEARCH\\s*[:：]\\s*(.+)$");

    /** 多轮记忆：单实例内存版，重启清空——个人用途够用 */
    private final Map<String, ArrayDeque<String[]>> history = Collections.synchronizedMap(
            new HashMap<>());

    @RequestMapping({"/", "/genie"})
    public ResultModel<TaskResult> chat(@RequestBody String json) {
        log.info("genie request: {}", json);
        TaskQuery taskQuery = MetaFormat.parseToQuery(json);

        TaskResult result = new TaskResult();
        String intent = taskQuery.getIntentName();
        if ("chat".equals(intent)) {
            String userText = taskQuery.getSlotEntities().stream()
                    .filter(s -> SLOT_NAME.equals(s.getIntentParameterName()))
                    .map(s -> s.getOriginalValue())
                    .findFirst()
                    .orElse("");
            if (userText == null || userText.isEmpty()) {
                result.setReply("我在听，请说完整一点。");
            } else {
                String token = String.valueOf(taskQuery.getToken());
                log.info("[CHAT] user={} text={}", token, userText);
                result.setReply(askLlm(token, userText));
            }
        } else if ("welcome".equals(intent)) {
            result.setReply("你好，我在。想聊点什么？");
        } else if ("task".equals(intent)) {
            // 异步深度研究：立即应答确认，后台跑迭代搜索，完成后推送播报
            String taskText = taskQuery.getSlotEntities().stream()
                    .filter(s -> SLOT_NAME.equals(s.getIntentParameterName()))
                    .map(s -> s.getOriginalValue())
                    .findFirst()
                    .orElse("");
            if (taskText == null || taskText.isEmpty()) {
                result.setReply("想让我研究什么，请说完整一点。");
            } else if (!PushNotifier.configured()) {
                result.setReply("深度研究功能需要先配置消息推送，还没配置好。");
            } else {
                log.info("[TASK SUBMIT] {}", taskText);
                ResearchService.submit(taskText);
                result.setReply("好的，我去查资料了，弄完念给你听，稍等一两分钟。");
            }
        } else {
            result.setReply("这个意图我还没实现，请到平台上补充。");
        }
        return reply(result);
    }

    /** 主流程：一次调用直接答；模型要求搜索时→搜→带结果再答 */
    private String askLlm(String token, String userText) {
        ArrayDeque<String[]> deque = history.computeIfAbsent(token, k -> new ArrayDeque<>());
        String apiKey = env("LLM_API_KEY", "");
        String apiBase = env("LLM_API_BASE", DEFAULT_API_BASE);
        String model = env("LLM_MODEL", DEFAULT_MODEL);
        if (apiKey.isEmpty()) {
            return "服务器还没配置大模型 API Key。";
        }

        try {
            JSONArray messages = baseMessages(SYSTEM_PROMPT, deque);
            messages.add(msg("user", userText));
            String first = callChat(apiBase, apiKey, model, messages);
            if (first == null) {
                return "大模型接口出错了，请稍后再试。";
            }

            String keyword = parseSearchKeyword(first);
            if (keyword == null) {
                return finish(first, deque, userText);   // 闲聊直答，无额外延迟
            }

            log.info("[SEARCH] keyword={}", keyword);
            String searchContext = tavilySearch(keyword);
            if ("NO_KEY".equals(searchContext)) {
                return "这个问题需要联网搜索，但我还没配置搜索服务。";
            }
            if (searchContext == null) {
                return "我查了一下网络，但搜索服务出了点问题，请稍后再问。";
            }

            JSONArray messages2 = baseMessages(SYSTEM_SEARCH_ANSWER, deque);
            messages2.add(msg("user",
                    userText + "\n\n以下是网络搜索结果（仅供参考，注意时效）：\n" + searchContext));
            String answer = callChat(apiBase, apiKey, model, messages2);
            if (answer == null) {
                return "大模型接口出错了，请稍后再试。";
            }
            return finish(answer, deque, userText);
        } catch (Exception e) {
            log.error("[LLM EX] model={} {}", model, e.toString());
            return "我这边网络开小差了，请再问一次。";
        }
    }

    /** 指定的system提示 + 历史消息（两跳各用各的提示词） */
    private JSONArray baseMessages(String systemPrompt, ArrayDeque<String[]> deque) {
        JSONArray messages = new JSONArray();
        messages.add(msg("system", systemPrompt));
        for (String[] m : deque) {
            messages.add(msg(m[0], m[1]));
        }
        return messages;
    }

    /** 截断到播报长度并写入多轮记忆 */
    private String finish(String answer, ArrayDeque<String[]> deque, String userText) {
        answer = answer == null ? "" : answer.replaceAll("\\s+", " ").trim();
        if (answer.isEmpty()) {
            return "我一时没想好怎么说，换个问法试试。";
        }
        if (answer.length() > MAX_REPLY_LEN) {
            answer = answer.substring(0, MAX_REPLY_LEN) + "……就说这么多。";
        }
        deque.add(new String[]{"user", userText});
        deque.add(new String[]{"assistant", answer});
        while (deque.size() > HISTORY_LIMIT) {
            deque.pollFirst();
        }
        return answer;
    }

    /** 调 chat/completions，成功返回 content，失败记日志返回 null（研究服务复用） */
    static String callChat(String apiBase, String apiKey, String model, JSONArray messages) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", 0.7);

            HttpURLConnection conn = (HttpURLConnection) new URL(apiBase).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                resp = reader.lines().collect(Collectors.joining("\n"));
            }
            if (code != 200) {
                log.error("[LLM ERR] HTTP {} model={} {}", code, model, resp);
                return null;
            }
            return JSON.parseObject(resp)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
        } catch (Exception e) {
            log.error("[LLM EX] model={} {}", model, e.toString());
            return null;
        }
    }

    /** Tavily 搜索：返回拼接的结果文本；NO_KEY=未配置；null=调用失败（研究服务复用） */
    static String tavilySearch(String query) {
        String key = env("TAVILY_API_KEY", "");
        if (key.isEmpty()) {
            return "NO_KEY";
        }
        try {
            JSONObject body = new JSONObject();
            body.put("query", query);
            body.put("max_results", 5);
            body.put("search_depth", "basic"); // 1积分/次，免费额度省着用

            HttpURLConnection conn = (HttpURLConnection) new URL(TAVILY_URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.toJSONString().getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String resp;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    code >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                    StandardCharsets.UTF_8))) {
                resp = reader.lines().collect(Collectors.joining("\n"));
            }
            if (code != 200) {
                log.error("[SEARCH ERR] HTTP {} {}", code, resp);
                return null;
            }
            JSONArray results = JSON.parseObject(resp).getJSONArray("results");
            if (results == null || results.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.size() && i < 5; i++) {
                JSONObject r = results.getJSONObject(i);
                String content = r.getString("content");
                if (content != null && content.length() > 300) {
                    content = content.substring(0, 300);
                }
                sb.append(i + 1).append(". ").append(r.getString("title"))
                        .append("\n").append(content).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[SEARCH EX] {}", e.toString());
            return null;
        }
    }

    /** 从模型第一跳输出里解析 SEARCH 指令；不是搜索指令返回 null */
    private static String parseSearchKeyword(String reply) {
        if (reply == null) {
            return null;
        }
        Matcher m = SEARCH_PREFIX.matcher(reply.trim());
        if (!m.find()) {
            return null;
        }
        String kw = m.group(1).split("\n")[0].trim()
                .replaceAll("^[\"'“”]+|[\"'“”]+$", "");
        return kw.isEmpty() ? null : kw;
    }

    private static String env(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }

    static JSONObject msg(String role, String content) {
        JSONObject o = new JSONObject();
        o.put("role", role);
        o.put("content", content);
        return o;
    }

    private ResultModel<TaskResult> reply(TaskResult result) {
        result.setExecuteCode(ExecuteCode.SUCCESS);
        // RESULT=本次应答即结束（下次需再说调用词）；想连续对话可改成 ASK_INF
        result.setResultType(ResultType.RESULT);
        ResultModel<TaskResult> res = new ResultModel<>();
        res.setReturnCode("0");
        res.setReturnValue(result);
        return res;
    }
}
