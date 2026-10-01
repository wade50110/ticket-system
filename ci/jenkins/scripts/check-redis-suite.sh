#!/usr/bin/env bash
# Redis 整合測試閘門(v0.7 CI/CD,需求書 F-6 / 決策 #9)。
# QuotaRedisRepositoryRedisTest 在 @BeforeAll 的 Assumption 失敗(連不到 localhost:6380)時會整類被跳過,
# surefire 報告會寫成 tests="0" skipped="0" —— 不是 skipped>0,所以要用 tests>0 判斷,否則 CI 會靜默放行。
# 用法:check-redis-suite.sh <TEST-com.example.ticket.stock.QuotaRedisRepositoryRedisTest.xml>
# 回傳 0 = 真的跑了且全過;非 0 = 沒跑/被跳過/有失敗。
set -u

report="${1:?用法:check-redis-suite.sh <surefire TEST-xxx.xml>}"

if [ ! -f "$report" ]; then
    echo "::Redis 閘門失敗:找不到報告 $report(整合測試類沒有執行)"
    exit 1
fi

# 取 <testsuite ...> 開頭標籤(可能跨行,先去掉換行)
tag=$(tr -d '\n\r' < "$report" | grep -o '<testsuite[^>]*>' | head -n 1)
if [ -z "$tag" ]; then
    echo "::Redis 閘門失敗:報告 $report 沒有 <testsuite> 標籤"
    exit 1
fi

attr() {
    printf '%s' "$tag" | sed -n "s/.*[[:space:]]$1=\"\([0-9]*\)\".*/\1/p"
}
tests=$(attr tests)
failures=$(attr failures)
errors=$(attr errors)
skipped=$(attr skipped)

echo "Redis 整合測試報告:tests=${tests:-?} failures=${failures:-?} errors=${errors:-?} skipped=${skipped:-?}"

if [ -z "$tests" ] || [ "$tests" -le 0 ]; then
    echo "::Redis 閘門失敗:tests=0,整合測試被整類跳過(CI Redis 沒連上 localhost:6380?)"
    exit 1
fi
if [ "${failures:-0}" -ne 0 ] || [ "${errors:-0}" -ne 0 ] || [ "${skipped:-0}" -ne 0 ]; then
    echo "::Redis 閘門失敗:failures/errors/skipped 不為 0"
    exit 1
fi

echo "Redis 閘門通過:${tests} 顆整合測試實際執行且全數通過"
exit 0
