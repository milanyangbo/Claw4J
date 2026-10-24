#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 GitHub CLI（gh）包成 MCP server。

这是 ADR-0003 里二层保留场景 `github` 的迁移形态：1.x 是
`skills/github/scripts/github.py` 被调用方当成脚本执行（模型的话当 argv 传进去），
现在是 Java 侧只见两个工具名与它们的参数 schema，
`gh` 在哪、怎么装、认证了没有，都是这个进程自己的事。

只读：只导出查询类命令（issue 列表、PR 的 CI 状态、API 查询）。
能写的能力（开 issue、合 PR）不在这个 server 里——
"接进来的是不是只读能力"由端点声明权限时决定，而不是靠这里自觉。
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from mcp_stdio import Tool, run_cli, serve  # noqa: E402

TIMEOUT = 30


def require_gh():
    """gh 没装或没认证时给一句能照做的话，而不是一个堆栈。"""
    from shutil import which

    if which("gh") is None:
        return "未安装 gh CLI。安装：brew install gh（macOS）或 apt install gh（Debian/Ubuntu）。"
    status = run_cli(["gh", "auth", "status"], timeout=10)
    if status.startswith("命令失败"):
        return "gh 已安装但未认证：gh auth login\n（gh 自己的输出：%s）" % status
    return None


def issue_list(arguments):
    problem = require_gh()
    if problem:
        return problem
    limit = str(int(arguments.get("limit", 10)))
    return run_cli(["gh", "issue", "list", "--repo", arguments["repo"],
                    "--state", arguments.get("state", "open"), "--limit", limit])


def pr_checks(arguments):
    problem = require_gh()
    if problem:
        return problem
    return run_cli(["gh", "pr", "checks", arguments["pr"], "--repo", arguments["repo"]])


def api(arguments):
    problem = require_gh()
    if problem:
        return problem
    command = ["gh", "api", arguments["path"]]
    field = arguments.get("jq")
    if field:
        command += ["--jq", field]
    return run_cli(command)


if __name__ == "__main__":
    serve("claw4j-github", [
        Tool("github_issue_list",
             "列出一个 GitHub 仓库的 issues（只读）。需要本机装了 gh CLI 并认证过。",
             {"repo": {"type": "string", "description": "仓库，形如 owner/repo"},
              "state": {"type": "string", "description": "open / closed / all，默认 open"},
              "limit": {"type": "integer", "description": "最多几条，默认 10"}},
             issue_list, ["repo"]),
        Tool("github_pr_checks",
             "查看一个 PR 的 CI 状态（只读）。需要本机装了 gh CLI 并认证过。",
             {"repo": {"type": "string", "description": "仓库，形如 owner/repo"},
              "pr": {"type": "string", "description": "PR 号或分支名"}},
             pr_checks, ["repo", "pr"]),
        Tool("github_api",
             "调用 GitHub API 的任意只读端点（gh api）。需要本机装了 gh CLI 并认证过。",
             {"path": {"type": "string", "description": "API 路径，形如 repos/owner/repo"},
              "jq": {"type": "string", "description": "可选的 jq 表达式，用来挑字段"}},
             api, ["path"]),
    ])
