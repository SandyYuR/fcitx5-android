# dispatch 定位记录（2026-10-16，第四包，冷启动 pid 12201）

## 启动链（14:43:38）

```
DirectRimeStart: enter state=STOPPED
DirectRimeStart: sync begin
DirectRimeStart: sync end
DirectRimeStart: setup=true ... version=1.17.0
DirectRimeStart: session=-5476376609857837672
DirectRimeStart: ready
```

## 按键链（DirectRimeKey，门控状态每次 maint=false hasSession=true）

| 按键 | dispatch | 说明 |
|---|---|---|
| kc=0 meta=536870912 uni=65364 | 3 | PASSTHROUGH |
| kc=49 meta=536870912 uni=0 | 3 | PASSTHROUGH |
| kc=23 meta=536870912 uni=0 | 2 | DROP（功能键无映射，正常） |
| kc=35 meta=536870912 uni=0 | 3 | PASSTHROUGH |
| kc=30 meta=0 uni=97（即字母 a） | 3 | PASSTHROUGH，commit=null preedit=null cands=0 |
| kc=30 meta=0 uni=97 up=true | 3 | 同上 |
| kc=24 meta=536870912 uni=0 | 2 | DROP |

## 结论

- 与 s-dlog 预测不符（预测 SWALLOW 常驻，实际为 3/2，无一次 SWALLOW），已记为预测落空：
  门控未误吞（maint=false hasSession=true），映射对字母键正常（keysym 非零否则应为 DROP）。
- 按键进了 librime，`process_key` 回 false → PASSTHROUGH。
  结合包内 `rime-data` 仅 4 文件、`default.yaml` 中 `schema_list: []`（无可用方案），
  根因为无方案可跑，引擎空转。下游修法 = 打入可用方案，而非改门控/映射/回流。
