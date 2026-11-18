package io.github.claw4j.app.doctor;

import io.github.claw4j.capability.secret.SpringSecretResolver;
import io.github.claw4j.secret.SecretResolver;
import io.github.claw4j.secret.SecretValue;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;

/**
 * 凭据句柄能不能解析成真值——**只解析，不发请求**。
 *
 * <p>这一项分开两种完全不同的故障："句柄名写错了 / 环境里没这把钥匙"（解析不出来，这里报）
 * 与"key 过期了或被吊销"（能解析，但发请求才 401，归 P18b）。合成一句"凭据有问题"，
 * 人就得自己去猜该改配置还是找运维换 key。
 *
 * <p>判据只有一条：{@link SecretValue#handles()} 里的每个名字，在 {@code Environment} 里都查得到。
 * 查不到时报的是**环境变量名**（{@code SecretResolver#environmentName}），不是句柄名——
 * 两句看着像，但该去改的是环境变量。
 */
public final class SecretHandleCheck implements DoctorCheck {

    /** 模型供应商与它们的配置键。通道凭据不走句柄（它们是 {@code ${VAR:}} 占位），不在这里。 */
    private static final List<String> PROVIDERS = List.of("deepseek", "qwen");

    @Override
    public String title() {
        return "凭据句柄";
    }

    @Override
    public List<Finding> run(Environment environment) {
        SecretResolver resolver = SpringSecretResolver.of(environment);
        List<Finding> findings = new ArrayList<>();
        for (String provider : PROVIDERS) {
            String key = "claw4j.model." + provider + ".api-key";
            String raw = environment.getProperty(key);
            if (raw == null || raw.isBlank()) {
                findings.add(Finding.skip(provider, key + " 没配，这一家没启用"));
                continue;
            }
            SecretValue handle;
            try {
                handle = SecretValue.parse(raw, key, false);
            }
            catch (IllegalArgumentException wrongShape) {
                findings.add(Finding.fail(provider, key + " 的形状不对：" + wrongShape.getMessage(),
                        "写成句柄 secret:<名字>（名字是小写字母、数字与 . _ -），真值放环境变量里"));
                continue;
            }
            findings.add(resolve(provider, handle, resolver));
        }
        return findings;
    }

    private static Finding resolve(String provider, SecretValue handle, SecretResolver resolver) {
        List<String> missing = new ArrayList<>();
        for (String name : handle.handles()) {
            if (resolver.lookup(name).isEmpty()) {
                missing.add(SecretResolver.environmentName(name));
            }
        }
        if (missing.isEmpty()) {
            return Finding.pass(provider, "句柄 " + String.join("、", handle.handles())
                    + " 能解析（只查了环境，没发请求）");
        }
        return Finding.fail(provider, "环境里没有这些变量：" + String.join("、", missing),
                "把真值写进环境或 .env（键名就是上面列的那些），或者把 " + provider
                        + " 的 api-key 去掉、换另一家");
    }
}
