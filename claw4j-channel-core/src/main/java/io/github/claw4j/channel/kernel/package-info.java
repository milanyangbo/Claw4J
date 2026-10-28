/**
 * 入站编排：平台回调进来之后、Agent 算完之后，那两段与平台无关的逻辑。
 *
 * <p>这里的东西解决的是**同一个问题在三个平台上是同一道题**：
 * 回调窗口只有几秒（飞书 3 秒、Telegram 约 7 秒、Slack 3 秒），一次 Text-to-SQL 却要十几秒。
 * 于是每个通道都要做同样四件事——立刻 ACK、按幂等键去重、认人、异步算完再回推。
 * 写第二遍的时候就会开始走形（少一个去重、忘了失败也回一句），所以它只能有一份。
 *
 * <p>模块边界：本包不依赖任何 IM SDK。判据是 {@code claw4j-channel-core/pom.xml} 里
 * 只有 {@code claw4j-domain} 与 slf4j——想看"这套编排是不是真通用"，看那个文件就够了。
 *
 * <p>**不在这里的东西**：HTTP 通道。它的请求本来就能同步等到答案，
 * 加一层异步只会让 {@code curl} 拿到 202 而不是结果，所以它直接调 {@code AgentPort}。
 */
package io.github.claw4j.channel.kernel;
