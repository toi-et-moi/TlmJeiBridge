# 帕秋莉的祭坛助手 — TLM JEI Bridge

一键合成车万女仆祭坛配方。One-click transfer altar recipes from JEI to the Touhou Little Maid altar.
作者是咱，也就是威严な大小姐（toi-et-moi）和deepseek

## 依赖

- Minecraft 1.20.1 + Forge 47.4.0+
- JEI 15.20+
- Touhou Little Maid（运行时，编译时不需要）

## 构建

```bash
# 先构建 TLM 并复制 jar 到 libs 目录
cd ../TouhouLittleMaid-1.20
./gradlew build
cp build/libs/touhou_little_maid-*.jar ../TlmJeiBridge/libs/

# 然后构建本模组
cd ../TlmJeiBridge
./gradlew build
```

## 功能

生存模式时在祭坛附近打开 JEI 查看祭坛配方时，JEI 自带的 "+" 号会出现。
点击即可从背包自动放置材料到祭坛 6 个基座上，随后自动合成并掉落结果。

### 材料来源（按搜索顺序）

1. **玩家背包** — 主背包、快捷栏、副手
2. **L2Backpack** — 普通背包 ✓ / 次元存储、末影背包、抽屉（部分支持）
3. **SophisticatedBackpacks** — 各级精妙背包 ✓
4. **Curios 饰品栏** — 安装在饰品栏的背包 ✓
5. **周围容器** — 箱子、桶等（默认 32 格半径）

### 配置

`tlmjei-common.toml` 在 `.minecraft/config/` 下：

```toml
[altar]
    # 祭坛检测范围（格），默认 8，范围 1-64
    searchRange = 8.0

[containers]
    # 容器材料搜索范围（格），0 = 禁用，默认 32，范围 0-64
    searchRange = 32.0
```
