package io.github.claw4j.domain.principal;

import java.util.Locale;
import java.util.Optional;

/**
 * 会话级的提权档（ADR-0030 裁决六，照 OpenClaw 的 {@code /elevated}）。
 *
 * <p>它回答的是"这一段对话里，命令跑在沙箱外还是沙箱里，以及审批还要不要问"。三档而不是四档：
 * OpenClaw 的 {@code ask} 是 {@link #ON} 的别名（它自己文档里就写着两者同义），
 * 多留一个取值只会让"on 与 ask 到底差在哪"成为一个永远要解释的问题。
 *
 * <p><b>它只在沙箱化时改变执行位置</b>：没有沙箱的部署里命令本来就在宿主上跑，
 * {@link #ON} 与 {@link #OFF} 的差别只剩"记了一个状态"。{@link #FULL} 例外——它跳审批，
 * 与有没有沙箱无关。
 *
 * <p>{@link #FULL} 是这条命令里唯一危险的一档，所以它的三重门在
 * {@code ElevatedCommand} 里：{@code enabled} 开着、发送者在上一次名单里、以及它只对<b>这一段会话</b>
 * 生效。默认值 {@link #OFF} 与"读不出来"同义：解析失败不猜。
 */
public enum Elevation {

    /** 不提权：按部署配置跑（有沙箱进沙箱，审批照常问）。默认。 */
    OFF,

    /** 跑到沙箱外，但<b>审批照旧</b>。OpenClaw 的 {@code ask} 是它的别名。 */
    ON,

    /** 跑到沙箱外，且<b>跳过审批</b>。只有被点名的人、在开着的部署里能用。 */
    FULL;

    /**
     * 解析一个取值。
     *
     * @param text 用户给的词，可以为 {@code null}
     * @return 认识时返回档位，不认识时为空——调用方据此回一句"用法"而不是猜一个
     */
    public static Optional<Elevation> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "off" -> Optional.of(OFF);
            case "on", "ask" -> Optional.of(ON);
            case "full" -> Optional.of(FULL);
            default -> Optional.empty();
        };
    }

    /**
     * 写进日志与回执的那个词。
     *
     * @return 小写取值
     */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
