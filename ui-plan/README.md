# UiPlan（可选）

`meguviz-ui-plan:1.2.0` 已发布到 Maven Central（纳入 `meguviz-bom:1.2.0`），源码构建通过 `-PenablePlan=true` 启用。

`UiPlan` 面向需要动态配置或重复模板挂载的界面；普通静态界面直接使用 `FrameScope` / `LinearScope` 等 DSL 即可。计划仅包含稳定组件 ID、schema、父容器类型、尺寸和不可变 `PlanNode` 树，不持有 `Context`、`View`、工厂或回调。

## 分阶段执行流程

1. `validate()`：限制节点数（默认 `4096`）与树深度（默认 `64`），拒绝空白/重复兄弟 key 和未知 schema 版本。
2. `resolve(registry, components)`：校验父种类、组件 schema 与容器资格，并从会话所属 `RegistrySnapshot` 一次性解析工厂句柄。
3. `bind(session, root, rootKind)`：校验实际父容器类型。
4. `mount()`：在主线程构造并挂载原生 View；中途抛异常时移除已挂载节点并合并清理异常。

已绑定的 `BoundUiPlan` 只能挂载一次（或调用 `close()` 放弃）；`MountedUiPlan` 由调用方在释放时关闭，支持通过 `ref(path, type)` 按路径与精确 `ComponentType` 获取类型化 `ViewRef`。`PlanComponent` 归应用所有，不同父容器种类须分别注册。

## 源码验证

```sh
./gradlew -PenablePlan=true :ui-plan:testDebugUnitTest :ui-plan:lintDebug :samples:plan-consumer:assembleDebug :samples:plan-consumer:assembleDebugAndroidTest
```

`samples/plan-consumer` 的仪器测试对比了直接 DSL 与 `UiPlan` 的类型、几何和中途失败回滚；是否在大规模动态页面中启用，应结合实际业务的节点数和构造耗时决定。
