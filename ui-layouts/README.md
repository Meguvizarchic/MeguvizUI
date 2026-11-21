# 原生行布局

`meguviz-ui-layouts:1.2.0` 已发布到 Maven Central（纳入 `meguviz-bom:1.2.0`），源码构建通过 `-PenableLayouts=true` 启用。

`nativeRow` 复用成熟的 Android `LinearLayout` 并强制 `orientation = LinearLayout.HORIZONTAL`，完整保留原生子控件测量、margin、baseline、RTL、焦点与无障碍语义。本模块为纯结构与约束辅助，不改变 `onMeasure` 次数或测量实现。

使用时通过 `NativeRowConstraints(maxChildren)` 限制初始子节点数量（默认 `8`，允许范围 `1..32`），越界将在挂载时撤销此次行挂载；构建完成后直接操作原生子控件时仍须由调用方遵守布局约束。跨系统版本、字号缩放与 RTL 几何表现应以真机测试为准。
