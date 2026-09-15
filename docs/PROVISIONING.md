# 设备预置指南（PROVISIONING）

> 状态：草案 v0.1
> 目标：把容器 App 预置为**设备管理员形态的工作台**，以获得"非 root 下等同于 root 的最大权限"。

---

## 1. 为什么需要预置

控制面能力（Device Owner / 无障碍 / Shizuku / 特殊权限）**无法经内核包热更新获得**——它们是焊死在设备 + APK 里的。必须在首次部署时一次性预置。这决定了产品是 **MDM / 设备管理员形态**，而非普通消费级商店 App。

## 2. 权限栈（非 root 最大权限组合）

| 能力 | 机制 | 启用方式 | 提供的能力 |
|---|---|---|---|
| 系统策略级 | **Device Owner**（DPC） | `dpm set-device-owner` / NFC / QR | 静默装卸应用、强制密码、锁屏/擦除、kiosk、代授特殊权限 |
| UI 自动化 | **AccessibilityService** | 设置→无障碍→开启本服务 | 读屏节点树、全局手势/点击/输入（"打开任意应用操作"引擎） |
| Shell 级 | **Shizuku / 无线调试** | 启动 Shizuku（Android 11+ 无线调试可免 PC） | shell(uid 2000) 跑 `input`/`am`/`pm`/`settings` |
| 屏幕感知 | **MediaProjection** | 运行时用户授权（Android 14+ 可常驻前台服务） | 截屏/录屏，供视觉 Agent |
| 常规特殊权限 | 通知访问 / 使用情况 / `MANAGE_EXTERNAL_STORAGE` / 悬浮窗 / 免打扰 | 各自在设置开启 | 读通知、用量、全盘文件、overlay、静音 |

> 组合 = **DO + Accessibility + Shizuku + MediaProjection + 标准特殊权限**，是"控系统/自动化/编译"目的下非 root 的实际上限。

## 3. 预置流程

### 3.1 设为 Device Owner（核心，一次性）
```bash
# 通过 ADB（设备已连电脑/同网无线调试）
adb shell dpm set-device-owner com.example.nodecontainer/.DeviceAdminReceiver
# 或出厂式：NFC/QR 配网（企业批量部署）
```
- 需先声明 `DeviceAdminReceiver` 与 `device_admin` 元数据，并在 `AndroidManifest.xml` 注册 DPC。
- 一旦设为 DO，**无法在设置里直接撤销**，需 `dpm remove-active-admin` 或恢复出厂。

### 3.2 开启 AccessibilityService
- `Settings → Accessibility → [本应用] → 开启`。
- 可经 DO 的 `setPermittedAccessibilityServices` 预授权，减少手动步骤。

### 3.3 启动 Shizuku
- 安装并启动 Shizuku App（或内置其服务），通过**无线调试**（Android 11+）授权一次；之后本应用经 Shizuku 获得 shell 级能力。

### 3.4 MediaProjection 授权
- 内核需要视觉感知时，由 Agent 触发系统授权弹窗；Android 14+ 可借前台服务常驻，减少重复授权。

### 3.5 特殊权限
- `MANAGE_EXTERNAL_STORAGE`：设置→应用→本应用→权限→所有文件访问。
- 通知访问、使用情况访问、悬浮窗、勿扰：对应设置页。

## 4. 验证（部署后自检）

复用 `RuntimeDiagnostics` 增加预置检查探针：
- `device-owner`：确认 `dpm` 状态为 active；
- `accessibility`：确认服务已启用；
- `shizuku`：确认 shell 通道可用（`shell.exec("echo ok")`）；
- `mediaprojection`：确认授权状态；
- `special-perms`：逐项确认。

全部 OK 后，工作台才具备"完整控制"能力面；任一缺失，对应桥方法返回 `ERR_CAPABILITY_MISSING`，Agent 降级运行。

## 5. 安全注意

- 预置需**用户知情与同意**（尤其无障碍、MediaProjection 属敏感权限，系统会明确提示风险）。
- Device Owner 形态仅供**自有设备 / 企业受管设备 / 用户明确授权**场景；不可用于未授权设备控制。
- 审计日志（见 BRIDGE_PROTOCOL §5）记录所有特权操作，便于追溯。
