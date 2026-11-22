package io.github.claw4j.agent.memory;

import io.github.claw4j.agent.advisor.AdvisorOrder;
import io.github.claw4j.domain.ContextKeys;
import io.github.claw4j.domain.principal.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springaicommunity.agent.dream.AutoDreamAdvisor;
import org.springaicommunity.agent.dream.AutoDreamService;
import org.springaicommunity.agent.dream.DreamTrigger;
import org.springaicommunity.agent.tools.task.repository.TaskRepository;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆整理：把库的 {@link AutoDreamAdvisor} 按人分发。
 *
 * <p>库那份到点整理已经有完整实现——{@code DreamTriggers.hoursAndSessions(h, n)} 判两个条件
 * （距上次整理够久、且又攒够 n 轮），会话计数落在 {@code <记忆目录>/.dream-state.json}，
 * 满足时在后台跑一次 {@code AutoDreamService} 的 dream 周期，由它带着记忆工具去合并与精简。
 * 它缺的跟读写记忆那份一样：<b>目录是构建期一个固定字符串</b>。
 *
 * <p>所以这里只做分发：{@code before} 什么都不改（库那份自己也不改请求，整理完全在带外），
 * {@code after} 拿这一轮的身份找到那个人的 dream advisor，把收尾交给它。**状态天然按人一份**
 * ——它跟着目录走。
 *
 * <p><b>身份从响应里取</b>：{@code after} 只拿到 {@link ChatClientResponse}，没有请求那份上下文。
 * 好在框架的 {@code ChatModelCallAdvisor} 会把请求上下文原样放进响应（流式由
 * {@code ChatClientMessageAggregator} 累积），所以 {@link ContextKeys#PRINCIPAL} 在那里读得到。
 * 读不到就什么都不做：不计数、不整理，也不猜一个人出来——错记一轮比漏记一轮代价大得多。
 */
public final class PrincipalDreamAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(PrincipalDreamAdvisor.class);

    private final Path root;

    private final AutoDreamService dreamService;

    private final TaskRepository dreamTasks;

    private final DreamTrigger dreamTrigger;

    /**
     * 每人一份：库那份把目录写在自己的字段里，所以一份只能服务一个人。
     *
     * <p>值是 {@link Optional} 而不是直接那个对象：目录建不出来时也要把这个"没有"记住，
     * 否则每一轮都会去试一次、每次都打一条日志。空值是个决定（这个人这次进程里不整理），
     * 不是"还没算过"。
     */
    private final Map<String, Optional<AutoDreamAdvisor>> advisors = new ConcurrentHashMap<>();

    /**
     * @param root 记忆根目录（与 {@code PrincipalMemoryAdvisor} 同一个）
     * @param dreamService 库的整理服务，所有用户共用（它自己带按目录的锁）
     * @param dreamTasks 后台任务表，dream 周期跑在上面
     * @param dreamTrigger 什么时候整理，取值见 {@code DreamTriggers}
     */
    public PrincipalDreamAdvisor(Path root, AutoDreamService dreamService,
                                 TaskRepository dreamTasks, DreamTrigger dreamTrigger) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.dreamService = Objects.requireNonNull(dreamService, "dreamService");
        this.dreamTasks = Objects.requireNonNull(dreamTasks, "dreamTasks");
        this.dreamTrigger = Objects.requireNonNull(dreamTrigger, "dreamTrigger");
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        // 整理完全在带外：这一轮请求一个字都不改（库那份也是这么做的）。
        return request;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        Principal principal = principalOf(response == null ? null : response.context());
        if (principal == null) {
            return response;
        }
        return advisorFor(principal)
                .map(advisor -> advisor.after(response, chain))
                .orElse(response);
    }

    @Override
    public int getOrder() {
        return AdvisorOrder.PRINCIPAL_DREAM;
    }

    /**
     * 这个人的记忆目录。与 {@code PrincipalMemoryAdvisor#directoryOf} 同一个路径。
     *
     * @param userId 通道侧的用户标识
     * @return 根目录下的一个子目录
     */
    public Path directoryOf(String userId) {
        return MemoryDirectories.under(root, userId);
    }

    private Optional<AutoDreamAdvisor> advisorFor(Principal principal) {
        return advisors.computeIfAbsent(principal.userId(), userId -> {
            Path directory = directoryOf(userId);
            try {
                // 库的状态文件（.dream-state.json）直接写在这个目录里、不会替我们建目录；
                // 不建的话第一个用户的第一次收尾就会炸在 after 里，把一轮正常的回答弄挂。
                // 记忆是附加信息，不是可用性依赖（与 FaultTolerantSessionService 同一个口径）。
                Files.createDirectories(directory);
            }
            catch (IOException failure) {
                log.warn("记忆目录建不出来，这个人的记忆整理本次进程里关掉：{}", directory, failure);
                return Optional.empty();
            }
            log.debug("记忆整理：用户 {} 的 dream 状态与记忆在 {}", userId, directory);
            return Optional.of(AutoDreamAdvisor.builder()
                    // 外面这层才是链上的位置；库那份的 order 只在它自己进链时才用得到。
                    .order(AdvisorOrder.PRINCIPAL_DREAM)
                    .memoriesRootDirectory(directory.toString())
                    .dreamTrigger(dreamTrigger)
                    .dreamService(dreamService)
                    .taskRepository(dreamTasks)
                    // 不传 userId：那一项是给 Dreamer 开 cross_session_search 用的。让它去翻
                    // 这个人的会话历史是另一层权限，整理记忆现在不需要。
                    .build());
        });
    }

    private static Principal principalOf(Map<String, Object> context) {
        Object value = context == null ? null : context.get(ContextKeys.PRINCIPAL);
        return value instanceof Principal principal ? principal : null;
    }
}
