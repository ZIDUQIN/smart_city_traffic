package com.smartcity.traffic.voice.llm;

import com.smartcity.traffic.voice.config.VoiceProperties;
import com.smartcity.traffic.voice.dto.StationCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 站名兜底：把"系统算出来的候选 + 用户原话"交给大模型，让它在候选里挑一个。
 *
 * <p><b>边界</b>：模型只能做这道选择题，不能生成候选以外的站名，也不参与"有没有票"的判断。
 * 返回值里的站名由调用方逐字比对候选名单，对不上就当它没说。</p>
 *
 * <p><b>失败即降级</b>：超时、401、返回不合法，一律返回 null，调用方继续用规则结果。
 * 模型是加分项，不是主路径。</p>
 */
@Component
public class StationJudgeClient {

    private static final Logger log = LoggerFactory.getLogger(StationJudgeClient.class);

    private static final String SYSTEM = """
            你是火车站售票窗口的老售票员，替一位可能不识字、只会说方言的老人办事。

            铁律：
            1. 你只能从"候选"里选站名。绝对不能自己编站名，也不能改写一个字。
            2. 候选里没有用户真正想去的地方，就填 null，并在 ask 里说一句追问的话。
            3. 不要判断有没有票、多少钱，那不是你的事。
            4. ask 要用老人听得懂的话，一句话，不超过 25 个字，不要书面语。
            5. 只输出 JSON，不要任何解释性文字。
            6. 库里查不到车，不代表站名说错了。用户说"上海"，你就按地名选上海的主要车站，
              不要因为"这条线没车"就填 null——那样用户会被反复追问站名，却始终得不到"今天没有这趟车"的答复。
               只有同一侧有好几个站都说得通、你确实分不出是哪一个时，才填 null 并 ask。
            """;

    private final ObjectProvider<ChatClient.Builder> builderProvider;

    private final VoiceProperties properties;

    private volatile ChatClient chatClient;

    public StationJudgeClient(ObjectProvider<ChatClient.Builder> builderProvider, VoiceProperties properties) {
        this.builderProvider = builderProvider;
        this.properties = properties;
    }

    /**
     * 让模型在候选里挑站名。
     *
     * @param text      用户原话（已归一化），模型据此判断错字
     * @param fromC     出发侧候选（已按相似度排序，最多 5 个）
     * @param toC       到达侧候选
     * @param reachable 当前库里能查到车的组合，例如 "宝鸡南→咸阳秦都"
     * @return 模型判断；调用失败或未启用时返回 null
     */
    public StationJudgeResult judge(String text, List<StationCandidate> fromC,
                                    List<StationCandidate> toC, List<String> reachable) {
        if (!properties.getLlm().isEnabled()) {
            return null;
        }
        ChatClient client = client();
        if (client == null) {
            log.debug("没有可用的对话模型，站名兜底跳过");
            return null;
        }

        try {
            CompletableFuture<StationJudgeResult> future = CompletableFuture.supplyAsync(() ->
                    client.prompt()
                            .system(SYSTEM)
                            .user(buildUserPrompt(text, fromC, toC, reachable))
                            .call()
                            .entity(StationJudgeResult.class));

            StationJudgeResult result = future.get(
                    Math.max(1, properties.getLlm().getTimeoutSeconds()), TimeUnit.SECONDS);
            if (result != null) {
                log.info("站名兜底模型返回：from={}, to={}, ask={}, why={}",
                        result.from(), result.to(), result.ask(), result.why());
            }
            return result;
        } catch (Exception e) {
            // 超时 / 401 / 返回不是合法 JSON 都走这里：记一条日志，然后当作模型没说话
            log.warn("站名兜底模型调用失败，退回规则结果：{}", e.toString());
            return null;
        }
    }

    private ChatClient client() {
        if (chatClient != null) {
            return chatClient;
        }
        ChatClient.Builder builder = builderProvider.getIfAvailable();
        if (builder != null) {
            chatClient = builder.build();
        }
        return chatClient;
    }

    private String buildUserPrompt(String text, List<StationCandidate> fromC,
                                   List<StationCandidate> toC, List<String> reachable) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("用户原话：").append(text).append("\n\n");

        sb.append("出发侧候选（按相似度排）：\n");
        for (StationCandidate c : fromC) {
            sb.append("- ").append(c.getStationName())
                    .append("（").append(fmt(c.getScore())).append("）\n");
        }

        sb.append("\n到达侧候选（按相似度排）：\n");
        for (StationCandidate c : toC) {
            sb.append("- ").append(c.getStationName())
                    .append("（").append(fmt(c.getScore())).append("）\n");
        }

        if (reachable.isEmpty()) {
            sb.append("\n已知：这些组合在当前库里都查不到车。\n");
        } else {
            sb.append("\n已知：下面这些组合能查到车：\n");
            for (String r : reachable) {
                sb.append("- ").append(r).append("\n");
            }
        }

        sb.append("""

                请判断用户说的出发站和到达站分别是候选里的哪一个。
                如果某个方向实在判断不出来，就填 null，并在 ask 里问一句。
                输出 JSON：{"from":"候选里的站名或 null","to":"候选里的站名或 null","ask":"追问的话或 null","why":"一句话理由"}
                """);
        return sb.toString();
    }

    private String fmt(Double score) {
        if (score == null) {
            return "0.00";
        }
        return String.format("%.2f", score);
    }
}
