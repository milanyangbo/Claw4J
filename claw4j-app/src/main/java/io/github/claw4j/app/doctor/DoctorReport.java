package io.github.claw4j.app.doctor;

import io.github.claw4j.app.doctor.DoctorCheck.Finding;
import io.github.claw4j.app.doctor.DoctorCheck.Status;

import java.util.List;

/**
 * 把若干条 {@link Finding} 排成一份人读的报告，并给出退出码。
 *
 * <p>格式不是为了好看，是为了三件事：
 * <ul>
 *   <li><b>失败排在最前</b>：修的人不用往下翻；</li>
 *   <li><b>修复动作跟在它自己那一行下面</b>：一段话配一条命令，不用对着编号回查；</li>
 *   <li><b>末尾一行给总数</b>：接进部署脚本时，人扫一眼就知道该不该继续。</li>
 * </ul>
 *
 * <p>纯函数：输入结果，输出字符串与 0/1。于是"一次报全"和"有 FAIL 就非 0"这两条能各用一个用例钉住，
 * 不必起真进程。
 */
public final class DoctorReport {

    private DoctorReport() {
    }

    /**
     * 排成可读文本。
     *
     * @param findings 各检查的结果；顺序会被重排（FAIL → SKIP → PASS）
     * @return 整份报告，含末尾的汇总行
     */
    public static String render(List<Finding> findings) {
        StringBuilder text = new StringBuilder();
        text.append("claw4j doctor —— 只查不用出去就能查的项（key 真的能用、MCP 连得上归 P18b）\n\n");
        for (Status status : List.of(Status.FAIL, Status.SKIP, Status.PASS)) {
            for (Finding finding : findings) {
                if (finding.status() != status) {
                    continue;
                }
                // 不按列对齐：项名里有中文，按字符数补空格在等宽字体里反而更乱。
                text.append("  %s  %s —— %s%n".formatted(status, finding.item(), finding.detail()));
                if (!finding.fix().isBlank()) {
                    text.append("      修复：%s%n".formatted(finding.fix()));
                }
            }
        }
        text.append('\n').append(summary(findings)).append('\n');
        return text.toString();
    }

    /**
     * 退出码：有任何一条 FAIL 就是 1，其余（含全 SKIP）是 0。
     *
     * <p>SKIP 不算失败：没配的那一层本来就不该拦部署。但它也不计入"通过"，
     * 免得"什么都没查"看起来像"都查过了"。
     *
     * @param findings 各检查的结果
     * @return 0 或 1
     */
    public static int exitCode(List<Finding> findings) {
        return findings.stream().anyMatch(finding -> finding.status() == Status.FAIL) ? 1 : 0;
    }

    private static String summary(List<Finding> findings) {
        long pass = findings.stream().filter(finding -> finding.status() == Status.PASS).count();
        long fail = findings.stream().filter(finding -> finding.status() == Status.FAIL).count();
        long skip = findings.stream().filter(finding -> finding.status() == Status.SKIP).count();
        String verdict = fail == 0 ? "没有不通过的项" : "有 " + fail + " 项不通过";
        return "%d 项：%d 通过 / %d 不通过 / %d 没查 —— %s".formatted(findings.size(), pass, fail, skip, verdict);
    }
}
