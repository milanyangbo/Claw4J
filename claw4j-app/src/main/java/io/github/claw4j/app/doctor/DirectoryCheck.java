package io.github.claw4j.app.doctor;

import org.springframework.core.env.Environment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 四个能力目录：配了就得存在，没配就是这一层不启用。
 *
 * <p><b>"没配"报 SKIP 而不是 PASS</b>：这两件事在别的命令里经常被混成一句"通过"，
 * 于是"我配了但路径写错"和"我没配"在输出上一模一样。doctor 的价值就是把这句分开——
 * 没配是选择，配错是故障。
 *
 * <p>它复查的是"启动会失败"的那一类：今天这些目录配了但不存在时，应用直接起不来，
 * 而启动是 fail-fast，只报第一个。
 */
public final class DirectoryCheck implements DoctorCheck {

    /** 键与它的中文名。顺序就是报告里的顺序，按"人最先会配的那个"排。 */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("claw4j.agent.directory", "人设目录"),
            new Entry("claw4j.skills.directory", "技能目录"),
            new Entry("claw4j.subagents.directory", "子 Agent 目录"),
            new Entry("claw4j.rag.directory", "知识库目录"));

    @Override
    public String title() {
        return "能力目录";
    }

    @Override
    public List<Finding> run(Environment environment) {
        List<Finding> findings = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            String configured = environment.getProperty(entry.key());
            if (configured == null || configured.isBlank()) {
                findings.add(Finding.skip(entry.label(), entry.key() + " 没配，这一层不启用"));
                continue;
            }
            Path directory = Path.of(configured).toAbsolutePath().normalize();
            if (Files.isDirectory(directory)) {
                findings.add(Finding.pass(entry.label(), directory + " 存在"));
            }
            else {
                findings.add(Finding.fail(entry.label(), entry.key() + " 指向的目录不存在：" + directory,
                        "建这个目录，或者去掉 " + entry.key() + " 这一行（去掉等于不启用这一层）"));
            }
        }
        return findings;
    }

    /**
     * 一个目录键。
     *
     * @param key   配置键
     * @param label 报告里显示的名字
     */
    private record Entry(String key, String label) {
    }
}
