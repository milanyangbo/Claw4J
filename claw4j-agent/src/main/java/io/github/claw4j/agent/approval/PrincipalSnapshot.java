package io.github.claw4j.agent.approval;

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
 * <p>续跑必须有它：批准之后要接着调模型，而工具调用的鉴权要一个 {@link Principal}。两个看起来省事
 * 的替代都不行——用审批人的身份续跑等于提权（审批人通常比提问人权限大，「我批你就能干」不是审批，
 * 是授权变更）；只存权限指纹又反推不出角色与可访问表（指纹是单向的，那正是它存在的理由）。
 *
 * <p>存快照而不是外键指向某个身份表：这个项目没有身份表，Principal 由通道解析入站消息后交进来。
 * 快照记的是「当时按哪份权限跑的」这个事实，不该随配置变化而变——三个月后改了角色配置，这次 run 的
 * 审计仍要能说清它当时的权限，而不是显示成「按现在的配置」。
 *
 * <p>读不出来时不抛：与 run 落库同一个降级口径。快照坏了的那件事续不上，于是它停在原地等人再问一句
 * ——比拿一个猜出来的身份继续跑安全得多。
 *
 * <p>之所以是 public：定时任务（P04）要用同一个形状——到点那一刻没有人发消息，鉴权同样要一份角色与
 * 可访问表。两处各写一份编码，早晚有一处漏了字段，而漏掉的那个（通常是 {@code actions}）要到第一次
 * 工具被拒时才暴露。
 */
public final class PrincipalSnapshot {

    private static final Logger log = LoggerFactory.getLogger(PrincipalSnapshot.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PrincipalSnapshot() {
    }

    /**
     * 存成一行 JSON。字段名短而稳定：它会进库、会被人直接 select 出来看。
     *
     * @param principal 发起人身份
     * @return 快照 JSON
     */
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
     * <p>缺字段就当成读不出来，不补默认值：补出来的 {@code role="guest"} 会让续跑以一个没人授予过的
     * 身份发生，而它看起来完全正常——只有工具调用被拒时才露出痕迹，那时离「快照坏了」已经很远。
     *
     * @param json 快照 JSON
     * @return 身份；读不出来时为 empty
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
