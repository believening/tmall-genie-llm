package com.genie;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步"深度研究"：承接 task 意图丢来的复杂问题，后台跑迭代搜索循环
 * （最多3轮 搜索→读结果→再搜索），产出总结后通过消息推送念给用户。
 *
 * 复用 GenieGlmController 的 chat/Tavily 调用，不引入新依赖；
 * 单线程池，跑完一个再跑下一个，避免免费额度被并发打爆。
 */
public final class ResearchService {

    private static final Logger log = LoggerFactory.getLogger(ResearchService.class);

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "research-worker");
        t.setDaemon(true);
        return t;
    });

    private static final int MAX_SEARCH_ROUNDS = 3;
    private static final int MAX_PUSH_LEN = 200;

    private static final String RESEARCH_PROMPT =
            "你是深度研究助手，正在为一位语音音箱用户做后台调研。规则："
            + "如果你掌握的信息还不够回答任务，只输出一行：SEARCH: 搜索关键词；"
            + "信息足够后，只输出：FINAL: 开头，后跟一段口语化的中文总结，不超过150字，"
            + "不要Markdown、列表或表情。最多只会给你3轮搜索机会，请合理分配。";

    private ResearchService() {
    }

    /** 提交一个研究任务（立即返回，后台执行，完成后推送） */
    public static void submit(String question) {
        POOL.submit(() -> run(question));
    }

    private static void run(String question) {
        log.info("[RESEARCH START] {}", question);
        String answer = null;
        try {
            String apiBase = envOr("LLM_API_BASE",
                    "https://opencode.ai/zen/v1/chat/completions");
            String model = envOr("LLM_MODEL", "deepseek-v4-flash-free");
            String apiKey = envOr("LLM_API_KEY", "");
            if (apiKey.isEmpty()) {
                log.error("[RESEARCH] no LLM_API_KEY");
                return;
            }

            StringBuilder context = new StringBuilder();
            for (int round = 0; round < MAX_SEARCH_ROUNDS; round++) {
                JSONArray messages = new JSONArray();
                messages.add(GenieGlmController.msg("system", RESEARCH_PROMPT));
                messages.add(GenieGlmController.msg("user",
                        "任务：" + question + "\n\n已收集的资料：\n" + context));

                String out = GenieGlmController.callChat(
                        apiBase, apiKey, model, messages);
                if (out == null) {
                    log.error("[RESEARCH] llm call failed at round {}", round);
                    return;
                }
                out = out.trim();

                if (out.toUpperCase().startsWith("FINAL")) {
                    answer = out.substring(5).replaceFirst("^[\\s:：]+", "");
                    break;
                }
                String keyword = extractKeyword(out);
                if (keyword == null) {
                    // 模型既没SEARCH也没FINAL：当作直接给出的结论
                    answer = out;
                    break;
                }
                log.info("[RESEARCH] round={} keyword={}", round, keyword);
                String result = GenieGlmController.tavilySearch(keyword);
                if (result == null || "NO_KEY".equals(result)) {
                    log.warn("[RESEARCH] search unavailable, answer from model directly");
                    answer = out;
                    break;
                }
                context.append("[第").append(round + 1).append("轮搜索：")
                        .append(keyword).append("]\n")
                        .append(result).append('\n');
            }

            if (answer == null) {
                // 3轮搜完仍没给FINAL：强制总结一次
                JSONArray messages = new JSONArray();
                messages.add(GenieGlmController.msg("system", RESEARCH_PROMPT));
                messages.add(GenieGlmController.msg("user",
                        "任务：" + question + "\n\n已收集的资料：\n" + context
                                + "\n\n搜索机会已用完，请现在输出 FINAL: 总结。"));
                String out = GenieGlmController.callChat(
                        apiBase, apiKey, model, messages);
                answer = (out == null) ? null
                        : out.trim().replaceFirst("(?i)^FINAL\\s*[:：]?", "").trim();
            }

            if (answer == null || answer.isEmpty()) {
                log.error("[RESEARCH] no answer produced");
                PushNotifier.push("你交给我的任务研究失败了，请稍后再试一次。");
                return;
            }
            answer = answer.replaceAll("\\s+", " ").trim();
            if (answer.length() > MAX_PUSH_LEN) {
                answer = answer.substring(0, MAX_PUSH_LEN) + "……详情后面再说。";
            }
            log.info("[RESEARCH DONE] {}", answer);
            String err = PushNotifier.push("任务有结果了：" + answer);
            if (err != null) {
                log.error("[RESEARCH] push failed: {}", err);
            }
        } catch (Exception e) {
            log.error("[RESEARCH EX] {}", e.toString());
        }
    }

    /** 解析 "SEARCH: xxx"；非该格式返回 null */
    private static String extractKeyword(String out) {
        if (out == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?is)^\\s*SEARCH\\s*[:：]\\s*(.+)$")
                .matcher(out.trim());
        if (!m.find()) {
            return null;
        }
        String kw = m.group(1).split("\n")[0].trim()
                .replaceAll("^[\"'“”]+|[\"'“”]+$", "");
        return kw.isEmpty() ? null : kw;
    }

    private static String envOr(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
