package io.github.claw4j.domain.question;

import io.github.claw4j.domain.principal.Principal;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 交互提问：模型在**同一轮里**问人一句，等人答，拿到答案再往下走（照 OpenClaw 的 {@code ask_user}）。
 *
 * <p>它与审批是两件事，别混。审批是「这件事太重，要人先批」，本项目照 ADR-0027 走「登记 + 唤醒会话」，
 * 不挂起运行；提问是「这个决定只有人能下」，OpenClaw 自己就是运行内等待，而且写明用**进程内**生命周期
 * 而不是持久账本（{@code docs/tools/ask-user.md}「The wait is attributed to the original run … uses the
 * existing process-local question lifecycle, not a durable recovery ledger」）。所以这里会阻塞、会超时、
 * 重启即丢——这三条都是有意的。
 *
 * <p>端口放 core 的理由与 {@link io.github.claw4j.domain.port.ApprovalCards 审批卡片} 同一条：
 * 提问由 Agent 一侧发起（模型调工具），答复从通道一侧进来（{@code InboundDispatcher.accept}），
 * 而 {@code channel → core ← agent} 这条边（ADR-0010）决定了它不能认识任何一侧的具体实现。
 *
 * <p>三种结局，缺一个就会有人猜：
 * <ul>
 *   <li>{@link Status#ANSWERED} 拿到答案；</li>
 *   <li>{@link Status#NO_ANSWER} 超时或被中止——模型该按自己的判断继续，不是原地重试；</li>
 *   <li>{@link Status#UNSUPPORTED} 这个通道根本没有提问面——模型该改用普通文字问
 *       （OpenClaw 对不支持的面的原话："the agent asks in ordinary plain text instead"）。</li>
 * </ul>
 */
public interface QuestionGate {

    /**
     * 把问题推到这段对话，然后等答案。
     *
     * <p>会阻塞调用线程，所以只该从工具回调里调（那本来就是一个可以等的地方）。
     *
     * @param ask 问什么、问谁、在哪段对话里问、等多久
     * @return 三种结局之一，永不返回 {@code null}
     */
    Outcome ask(Ask ask);

    /**
     * 有人在某段对话里说了一句话：这是不是在回答那个待答问题？
     *
     * <p>不阻塞。回答的人必须与提问的人权限一致（OpenClaw："when your current permissions match the
     * creator's"），不一致时**不当答案**、返回 {@code false}，那条消息照常走一轮——
     * 让别人的一句话把问题答掉，等于绕过提问人手里的那个决定。
     *
     * @param channel 通道标识
     * @param chatRef 通道内的会话地址
     * @param who 说话的人
     * @param text 说的那句话
     * @return 消费掉了这句时 {@code true}（调用方别再把它当新的一轮）
     */
    boolean answer(String channel, String chatRef, Principal who, String text);

    /**
     * 这段对话里正在等答案的问题。没有推送面的通道（HTTP）靠它轮询。
     *
     * <p>默认实现返回空表：没有提问设施时它一条都没有，这比让每个实现都写一遍 {@code List.of()} 省事。
     *
     * @param channel 通道标识
     * @param chatRef 通道内的会话地址
     * @return 正在等的那条；没有时为空表
     */
    default List<Ask> pending(String channel, String chatRef) {
        return List.of();
    }

    /**
     * 没有提问设施的部署：什么都不问，直接告诉模型「这儿没人能答」。
     *
     * @return 一律 {@link Status#UNSUPPORTED} 的门
     */
    static QuestionGate unsupported() {
        return new QuestionGate() {

            @Override
            public Outcome ask(Ask ask) {
                return Outcome.unsupported();
            }

            @Override
            public boolean answer(String channel, String chatRef, Principal who, String text) {
                return false;
            }
        };
    }

    /**
     * 一次提问。
     *
     * @param channel 通道标识，与 {@code OutboundDelivery#channel()} 同口径
     * @param chatRef 通道内的会话地址；HTTP 是请求头里那个 chatId
     * @param creator 提问人。答复的人要与它权限一致
     * @param runId 这一轮的编号，进日志
     * @param questions 一到四个问题
     * @param timeout 最多等人多久；{@code null} 表示用部署的默认值（{@code claw4j.tools.ask.timeout-seconds}）
     */
    record Ask(String channel, String chatRef, Principal creator, String runId,
               List<Question> questions, Duration timeout) {

        public Ask {
            Objects.requireNonNull(creator, "creator");
            questions = List.copyOf(Objects.requireNonNull(questions, "questions"));
            if (questions.isEmpty()) {
                throw new IllegalArgumentException("至少要有 1 个问题");
            }
        }
    }

    /**
     * 一道题。
     *
     * @param header 短标签（界面上当芯片用，比如"部署目标"）
     * @param text 完整问句
     * @param options 可选项；可以为空——空表示这是一句自由问答
     * @param multiSelect 能不能多选
     */
    record Question(String header, String text, List<Option> options, boolean multiSelect) {

        public Question {
            Objects.requireNonNull(text, "text");
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * 一个选项。
     *
     * @param label 选项名，用户可以直接回它
     * @param description 一句解释，可以为 {@code null}
     */
    record Option(String label, String description) {

        public Option {
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * 一次提问的结局。
     *
     * @param status 三种结局之一
     * @param answers 按**题目原文**分组的答案；非 {@link Status#ANSWERED} 时为空表
     */
    record Outcome(Status status, Map<String, List<String>> answers) {

        public Outcome {
            Objects.requireNonNull(status, "status");
            answers = answers == null ? Map.of() : Map.copyOf(answers);
        }

        /**
         * 拿到答案了。
         *
         * @param answers 按题目原文分组的答案
         * @return 结局
         */
        public static Outcome answered(Map<String, List<String>> answers) {
            return new Outcome(Status.ANSWERED, answers);
        }

        /**
         * 没人答（超时或被中止）。
         *
         * @return 结局
         */
        public static Outcome noAnswer() {
            return new Outcome(Status.NO_ANSWER, Map.of());
        }

        /**
         * 这个通道没有提问面。
         *
         * @return 结局
         */
        public static Outcome unsupported() {
            return new Outcome(Status.UNSUPPORTED, Map.of());
        }

        /**
         * 拿到答案了没有。
         *
         * @return {@link Status#ANSWERED} 时为 {@code true}
         */
        public boolean isAnswered() {
            return status == Status.ANSWERED;
        }
    }

    /** 三种结局。 */
    enum Status {

        /** 人答了。 */
        ANSWERED,

        /** 等了超时时间没人答，或者这一轮被中止了。模型按自己的判断继续。 */
        NO_ANSWER,

        /** 这个通道没有提问面：改用普通文字问。 */
        UNSUPPORTED
    }
}
