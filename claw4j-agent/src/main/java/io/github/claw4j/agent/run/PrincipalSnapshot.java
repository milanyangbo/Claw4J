package io.github.claw4j.agent.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 发起人身份的快照（P02）：进门时存一份，服务端续跑时原样取回。
 *
 * <p><b>为什么续跑非得有它</b>：批准之后要接着调模型，而工具调用的鉴权要一个
 * {@link Principal}。两个看起来省事的替代都不行——用审批人的身份续跑等于提权
 * （审批人通常比提问人权限大，"我批你就能干"不是审批，是授权变更）；
 * 只存权限指纹又反推不出角色与可访问表（指纹是单向的，那是它存在的理由）。
 *
 * <p><b>存快照而不是外键指向某个身份表</b>：这个项目没有身份表，Principal 由通道解析
 * 入站消息后交进来。快照记的是"当时按哪份权限跑的"这个事实，它不该随配置变化而变——
 * 三个月后改了角色配置，这次 run 的审计要能说清它当时是什么样的权限，
 * 而不是显示成"按现在的配置"。P07 的审计读的就是这个意思。
 *
 * <p><b>读不出来时不抛</b>：与 run 落库同一个降级口径。快照坏了的那件事续不上，
 * 于是它停在原地等人再问一句——比拿一个猜出来的身份继续跑安全得多。
 *
 * <p><b>为什么是 public</b>：定时任务（P04）要用<b>同一个形状</b>——到点那一刻没有人发消息，
 * 鉴权同样要一份角色与可访问表。两处各写一份编码，早晚有一处漏了字段，
 * 而漏掉的那个字段（通常是 {@code actions}）要到第一次工具被拒时才暴露。
 */
public final class PrincipalSnapshot {

    private static final Logger log = LoggerFactory.getLogger(PrincipalSnapshot.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrincipalSnapshot() {
    }

    /** 存成一行 JSON。字段名短而稳定：它会进库、会被人直接 select 出来看。 */
    public static String encode(Principal principal) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("userId", principal.userId());
        node.put("role", principal.role());
        ArrayNode tables = node.putArray("tables");
        principal.accessibleTables().forEach(tables::add);
        ArrayNode actions = node.putArray("actions");
        principal.allowedActions().forEach(actions::add);
        return node.toString();
    }

    /**
     * 读回一份身份。
     *
     * <p><b>缺字段就当成读不出来</b>，不补默认值：补出来的 {@code role="guest"} 会让
     * 续跑以一个没人授予过的身份发生，而它看起来完全正常——只有工具调用被拒时才露出痕迹，
     * 那时候离"快照坏了"已经很远了。
     */
    public static Optional<Principal> decode(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            String userId = text(node, "userId");
            String role = text(node, "role");
            if (userId == null || role == null) {
                log.warn("发起人快照缺少 userId 或 role，这件事不续跑");
                return Optional.empty();
            }
            return Optional.of(Principal.of(userId, role, set(node, "tables"), set(node, "actions")));
        } catch (Exception broken) {
            log.warn("发起人快照读不出来，这件事不续跑", broken);
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Set<String> set(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return Set.of();
        }
        Set<String> items = new LinkedHashSet<>();
        value.forEach(item -> items.add(item.asText()));
        return items;
    }
}
