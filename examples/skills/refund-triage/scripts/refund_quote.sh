#!/usr/bin/env bash
# 按售后政策算退款窗口与金额（确定性计算，不调模型、不出网）。
#
# 用法：refund_quote.sh --region east|south --days N --amount 元 [--quality]
# 退出码：0 算出来了；2 参数不合法。
set -euo pipefail

region=""
days=""
amount=""
quality="false"

while [ $# -gt 0 ]; do
  case "$1" in
    --region)  region="${2:-}"; shift 2 ;;
    --days)    days="${2:-}"; shift 2 ;;
    --amount)  amount="${2:-}"; shift 2 ;;
    --quality) quality="true"; shift ;;
    *) echo "不认识的参数：$1" >&2; exit 2 ;;
  esac
done

if [ -z "$region" ] || [ -z "$days" ] || [ -z "$amount" ]; then
  echo "缺参数：--region east|south --days N --amount 元 [--quality]" >&2
  exit 2
fi
case "$region" in
  east)  window=7;  zone="华东" ;;
  south) window=5;  zone="华南" ;;
  *) echo "--region 只能是 east 或 south，收到的是「$region」" >&2; exit 2 ;;
esac
case "$days" in
  ''|*[!0-9]*) echo "--days 要是整数天数，收到的是「$days」" >&2; exit 2 ;;
esac
case "$amount" in
  ''|*[!0-9.]*) echo "--amount 要是数字（元），收到的是「$amount」" >&2; exit 2 ;;
esac

# 金额只用于展示与"全额/部分"的判断，这里按全额退；用 awk 保证两位小数不丢精度。
money=$(awk -v a="$amount" 'BEGIN { printf "%.2f", a }')

if [ "$days" -le "$window" ]; then
  verdict="无理由窗口（${window} 天）内，可退"
  shipping="无理由退货由客户承担运费"
  refundable="true"
elif [ "$days" -le 15 ] && [ "$quality" = "true" ]; then
  verdict="无理由窗口（${window} 天）已过；属于质量问题，走 15 天窗口，需区域售后主管审批"
  shipping="质量问题我方承担运费"
  refundable="true"
elif [ "$days" -le 15 ]; then
  verdict="无理由窗口（${window} 天）已过，且未声明质量问题：不可退；若确属质量问题可走 15 天窗口"
  shipping="—"
  refundable="false"
else
  verdict="已超过 15 天，不可退（可问是否仍在保修期内）"
  shipping="—"
  refundable="false"
fi

if [ "$refundable" = "true" ]; then refund="${money}"; else refund="0.00"; fi

cat <<EOF
退款判定（售后政策 2026 版）
  区域：${region}（无理由 ${window} 天 / 质量问题 15 天）
  签收后：${days} 天
  结论：${verdict}
  可退货款：${refund}
  运费：${shipping}
  到账：验收通过后 3 个工作日原路退回，银行卡再 1-3 个工作日
  依据：after-sales.md ${zone}区 / 退款到账 / 退货、换货、保修三件事别混
EOF
