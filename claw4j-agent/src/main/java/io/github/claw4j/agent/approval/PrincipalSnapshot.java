package io.github.claw4j.agent.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 发起人身份的快照（P02）：进门时存一份，服务端续跑时原样取回。
 *
 * <p>续跑必须有它：批准之后要接着调模型，而那一轮要一个 {@link Principal} 才能说清"这是谁的事"。
 * 用审批人的身份续跑不行——审批人通常不是提问人，「我批你就能干」不是审批，是换个人在干活。
 *
 * <p>现在快照里只有一个字段（{@code userId}）。它曾经还存 {@code role} / {@code tables} /
 * {@code actions}，理由是"三个月后仍然要说得清这次 run 当时的权限"。权限那一层在 ADR-0030
 * 整块下线之后，这句话没有内容可说了；而 {@code role} 也从身份上删掉。**旧记录（带这三个字段）
 * 仍然读得出来**：多出来的键被忽略，缺 {@code userId} 才算读不出来——库里那些行不能因为一次
 * 收窄就全部作废（{@code ApprovalFlowTest#legacyApprovalRecordStillParses} 钉着这一条）。
 *
 * <p>读不出来时不抛：与 run 落库同一个降级口径。快照坏了的那件事续不上，于是它停在原地等人再问一句
 * ——比拿一个猜出来的身份继续跑安全得多。
 *
 * <p>之所以是 public：定时任务（P04）要用同一个形状——到点那一刻没有人发消息，也要说清"这是谁的
 * 任务"。两处各写一份编码，早晚有一处漏了字段。
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
        return node.toString();
    }

    /**
     * 读回一份身份。
     *
     * <p>缺 {@code userId} 就当成读不出来，不补默认值：补出来的身份会让续跑以一个没人授予过的
     * 身份发生，而它看起来完全正常。多余字段（旧记录里的 {@code role} / {@code tables} /
     * {@code actions}）直接忽略——那是收窄之前的形状，不是坏数据。
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
            if (userId == null || userId.isBlank()) {
                log.warn("发起人快照缺少 userId，这件事不续跑");
                return Optional.empty();
            }
            return Optional.of(new Principal(userId));
        } catch (Exception broken) {
            log.warn("发起人快照读不出来，这件事不续跑", broken);
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

}
