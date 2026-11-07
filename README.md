# 大小姐的祭坛助手 — TLM JEI Bridge

一键合成车万女仆祭坛配方。One-click transfer altar recipes from JEI to the Touhou Little Maid altar.

作者是咱，也就是威严な大小姐（toi-et-moi）和deepseek

## 版本

| Minecraft | 平台 | 分支 |
|---|---|---|
| 1.20.1 | Forge 47.4.0+ | `master` |
| 1.21.1 | NeoForge 21.1+ | `1.21.1` |

## 依赖

- Minecraft + Forge / NeoForge（对应版本）
- JEI（对应版本）
- Touhou Little Maid（运行时，编译时不需要）

## 构建

### 1.20.1 (master)

```bash
cd TlmJeiBridge
./gradlew build
```

### 1.21.1 (1.21.1 分支)

```bash
# 需要 JDK 21 + Gradle 8.14+
git checkout 1.21.1
./gradlew build
```

## 功能

生存模式时在祭坛附近打开 JEI 查看祭坛配方时，JEI 自带的 "+" 号会出现。
点击即可从背包自动放置材料到祭坛 6 个基座上，随后自动合成并掉落结果。
玩家背包、L2Backpack、精妙背包等界面中查看配方时同样可以使用 "+"。

### 材料来源（按搜索顺序）

1. **玩家背包** — 主背包、快捷栏、副手
2. **L2Backpack** — 普通背包、次元存储、末影背包、抽屉 ✓
3. **SophisticatedBackpacks** — 各级精妙背包 ✓
4. **Curios 饰品栏** — 安装在饰品栏的背包 ✓
5. **周围容器** — 箱子、桶等（默认 32 格半径）
   - 容器内的背包会被递归打开搜索

### 庭灯补魔

祭坛合成需要 P 点时，优先消耗玩家自身的 P 点；不足部分会从附近的庭灯
（ shrine lamp ）中按距离由近到远扣除。若庭灯储量不足以补足差额，则不会
扣除任何庭灯，保持原版行为。

### 配置

`tlmjei-common.toml` 在 `.minecraft/config/` 下：

```toml
[altar]
    # 祭坛检测范围（格），默认 8，范围 1-64
    searchRange = 8.0

[containers]
    # 容器材料搜索范围（格），0 = 禁用，默认 32，范围 0-64
    searchRange = 32.0

[shrineLamp]
    # 庭灯补魔搜索范围（格），0 = 禁用，默认 8，范围 0-64
    searchRange = 8.0
```
