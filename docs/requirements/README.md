# 需求書目錄

每個新功能一份需求書,開發前寫、定稿後才開發。完整流程與模板見 [`../../../.claude/skills/write-requirements.md`](../../../.claude/skills/write-requirements.md)。

## 組織方式:一個版本一個 folder

每一版(一次的需求)開一個 folder,folder 內放一份總覽 + 各功能的細項需求書:

```
requirements/
└── <版本>/                    例:v0.5/
    ├── README.md              這版總共改什麼(總覽,先看這份)
    ├── <功能代號>.md          細項需求書(去版本前綴,例:purchase-limit.md)
    └── <功能代號>-todo.md     開發開始時才建立,放在對應需求書旁
```

- **想看這版大概改什麼** → 看該版 folder 的 `README.md`;**想看細項** → 點進對應的 `<功能代號>.md`。
- 需求書狀態:草稿 → 已定稿 → 開發中 → 已完成。
- 決策紀錄寫在每份需求書的最後一章,不另開檔案。
- 功能完成後需求書即為歷史紀錄,現況以 [`../`](../)(docs/ 模組 spec)為準。

## 現有版本

- [`v0.5/`](v0.5/) — 防黃牛與流量控制(限購 ✅、訂單取消、rate limit)
- [`v0.6/`](v0.6/) — 可觀測性/監控(Prometheus + Grafana ✅ 已完成,現況見 [`../monitoring.md`](../monitoring.md))
