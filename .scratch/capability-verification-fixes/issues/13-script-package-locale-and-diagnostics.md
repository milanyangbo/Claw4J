# 13: 脚本包在 C locale 下打印中文直接崩，而失败时把唯一的诊断信息丢了

**What to build:** 脚本包的示例资产在任何 PATH / 任何 Python 3 上都跑得通；
并且脚本自己说了什么（stdout）要能看见，不能只剩 stderr 一句无关的警告。

**Blocked by:** 无。

**Type:** bug（示例资产 3 处 + 执行器 1 处可诊断性）

**Status:** resolved

**Demo:** 用**冲突的 PATH** 起实例（让子进程的 `python3` 落在 3.6.8 上），
按 `README-capability-tests.md` §3 那条命令发一轮，能拿到逐字原文而不是「退出码=1」。

```bash
PATH=/usr/local/bin:$PATH java -jar claw4j-app/target/claw4j-app-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=local --claw4j.scripts.directory=examples/scripts \
  --claw4j.cache.enabled=false
```

## 现场（用户按文档跑出来的原文）

```
summarize 处理 sample.md 时以退出码 1 失败，没有返回任何文本
stderr 是 summarize.py:39: FutureWarning: split() requires a non-empty pattern match.
```

模型只能看到这两句，所以它只能猜"真正的报错没出现在 stderr 里"。

## 根因（三层，缺一层都复现不出来）

1. **脚本层子进程只拿 `PATH`，没有 locale。** `SandboxPolicy.environment()` 返回
   `Map.of("PATH", System.getenv("PATH"))`——这是刻意的（不传凭据），但代价是子进程落在
   `C` locale 上。Python 3.7 以前没有 PEP 538 的 locale 强制转换，stdout 编码就是 **ASCII**，
   `print("摘要（…）")` 直接 `UnicodeEncodeError`。
2. **PATH 上排第一的 `python3` 是 3.6.8**（本机 `/usr/local/bin/python3`）。
   `run.sh` 里写的是裸 `python3`，于是拿到的是最老的那个。
3. **失败时 stdout 被整体丢弃。** `ScriptPackageRunner.run` 的注释写明"非零退出不把半截 stdout
   当结果交回去"——防的是"半截输出看起来像正常结果"。但脚本自己在 stdout 上打的是
   `读不到内容：…` / `没读到可摘要的正文。`，那是**诊断**，不是结果。全丢之后，
   模型手上只剩 stderr 那句 `FutureWarning`，指向一个跟真因无关的地方。

复现（`env -i` 模拟沙箱环境，只给 PATH）：

```
$ env -i PATH=/usr/local/bin:/usr/bin:/bin /usr/local/bin/python3 summarize.py sample.md
summarize.py:39: FutureWarning: split() requires a non-empty pattern match.
UnicodeEncodeError: 'ascii' codec can't encode characters in position 0-2: ordinal not in range(128)
exit=1
```

## 验收判据

| 判什么 | 判据 |
| --- | --- |
| 中文输出不再依赖 locale | 同一个 `env -i PATH=…` 环境下，三种版本（3.6 / 3.9 / 3.13）都 `exit=0` 且输出中文 |
| 断句行为与版本无关 | 3.6.8 与 3.13 输出的句子条数一致（现在 5 vs 7，因为老版本 `re.split` 对空匹配的处理不同） |
| 失败仍要能诊断 | `ScriptPackageRunner` 抛出的消息里带脚本 stdout 的第一行；stderr 非空时也带上 |
| 不把半截输出当结果 | 仍然是非零退出即失败，`run()` 的返回值不包含任何 stdout |

## 不能这样做

- 不要往沙箱环境里塞 `LANG` / `LC_ALL`：那份环境表的意义是"给了什么就是什么"，
  加一个与安全无关但跨平台取值不同的变量（macOS 没有 `C.UTF-8`），会把"看得见"变成"看运气"。
- 不要把 `PYTHONWARNINGS=ignore` 加进去：警告是信息，压掉它只是让下一次更难查。

## 实现记录

- `examples/scripts/summarize/run.sh`：入口那行改成 `PYTHONIOENCODING=utf-8 exec python3 …`，
  注释写清"环境里只有 PATH，没有 locale"。这一行是**读者要照抄的模板**，所以放在 run.sh 里
  而不是埋进 py。
- `examples/scripts/summarize/summarize.py`：断句从"在标点后 split 零宽匹配"改成
  `re.findall(r"[^。！？!?.；;]+[。！？!?.；;]?")`。原来那种写法在 3.6 上会告
  `FutureWarning` 且**切出来的句数不一样**（3.6 是 5 句、3.13 是 7 句）——脚本包的输出不该
  随宿主装的是哪个 python 变。
- `ScriptExecutionException`：消息从"退出码 + stderr"扩成"退出码 + stdout + stderr"（各取第一行
  非空内容）；两个流都空时写"两个流都是空的"——那意味着被信号杀掉或 exec 没起来，
  与"脚本打印了原因"是不同的结论，不该长得一样。
- 用例：`ScriptPackageTest#nonZeroExitBecomesAToolFailure` 的脚本改成两个流都打，断言两条都在
  （没有新增文件/方法，该文件 17 条）。
- **变异验证**：删掉消息里的 `stdout=` 那一段 → 只有这一条红，报错里点名
  `没有可摘要的正文。` 没出现在消息里。
- 端到端：用 `PATH=/usr/local/bin:$PATH` 起实例（让子进程的 `python3` 落到 3.6.8），
  §3 那条命令拿到逐字原文（`华南区为 5 天，需提供原包装；`）；再故意问 `nope.md`，
  回答里直接引用 `[Errno 2] No such file or directory: 'nope.md'`——以前这句在 stdout 上、
  被丢掉了，模型只能猜。
