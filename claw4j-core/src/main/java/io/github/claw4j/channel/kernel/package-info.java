/**
 * 入站编排：平台回调进来之后、Agent 算完之后，那两段与平台无关的逻辑。
 *
 * <p>三个平台在这里面对的是同一道题：回调窗口只有几秒（飞书 3 秒、Telegram 约 7 秒、
 * Slack 3 秒），一次 Text-to-SQL 却要十几秒。于是每个通道都要做同样四件事：立刻 ACK、
 * 按幂等键去重、认人、异步算完再回推。写第二遍就会走形（少一个去重、忘了失败也回一句），
 * 所以只留一份。
 *
 * <p>模块边界：本包所在的 {@code claw4j-core} 不依赖任何 IM SDK。判据是
 * {@code claw4j-core/pom.xml} 里只有 slf4j 一条编译期依赖，外加一段 validate 阶段的
 * enforcer。
 *
 * <p>它和 {@code domain} 在同一个 jar，因为两者都是"所有别的模块朝它依赖、自己谁也不依赖"
 * 的那一层；劈成两个坐标只会让接通道的人猜该写哪一个。合并记录见 docs/adr/0015，
 * 代价（插件多拿到 slf4j 与通道契约）也写在那里。
 *
 * <p>不在这里的是 HTTP 通道：它的请求本来就能同步等到答案，加一层异步只会让 {@code curl}
 * 拿到 202 而不是结果，所以它直接调 {@code AgentPort}。
 */
package io.github.claw4j.channel.kernel;
