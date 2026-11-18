package io.github.claw4j.app.doctor;

import org.springframework.core.env.Environment;

import java.util.List;

/**
 * doctor 的一项检查。
 *
 * <p>一个实现只查一类东西，互不依赖，也就互不拖累：某一项自己抛异常时，{@link Doctor}
 * 把它记成一条 FAIL 继续跑下一项——"一次报全"是这条命令唯一比启动日志强的地方，
 * 中途退出就退化成第二个 fail-fast。
 *
 * <p>每项返回的是<b>若干条</b>结果而不是一个：目录那一项要按每个键各报一行，
 * 否则"四个目录里哪个不存在"就得靠人从一句话里猜。
 */
public interface DoctorCheck {

    /** 这一项叫什么，用在报告的行首。 */
    String title();

    /**
     * 跑一遍。
     *
     * @param environment 配置来源（yml、profile、环境变量、导入的 .env 都在里面）
     * @return 若干条结果；空列表表示"这一项没东西可报"，报告里会显示成 SKIP
     */
    List<Finding> run(Environment environment);

    /** 一条结果的三档：通过 / 不通过 / 没查（没配、或这项本来就查不了）。 */
    enum Status {
        PASS, FAIL, SKIP
    }

    /**
     * 一条结果。
     *
     * @param item   查的是什么（"迁移""技能目录""deepseek 的凭据句柄"）
     * @param status 三档
     * @param detail 一句话结论：看到的事实，不是评价
     * @param fix    修复动作；PASS 时为空串。**只给动作，不自动执行**——doctor 是只读诊断
     */
    record Finding(String item, Status status, String detail, String fix) {

        /** 通过。 */
        public static Finding pass(String item, String detail) {
            return new Finding(item, Status.PASS, detail, "");
        }

        /** 不通过，带一句怎么修。 */
        public static Finding fail(String item, String detail, String fix) {
            return new Finding(item, Status.FAIL, detail, fix);
        }

        /** 没查：没配、或这一项在不启动上下文的前提下查不了。 */
        public static Finding skip(String item, String detail) {
            return new Finding(item, Status.SKIP, detail, "");
        }
    }
}
