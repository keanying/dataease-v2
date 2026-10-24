# 自研企业版（io.dataease.ee）开发说明

本目录说明在 DataEase V3 社区版之上自研的企业版功能。全部实现位于本仓库、按仓库 `LICENSE` 开源，**不包含也不依赖**飞致云闭源的 `de-xpack` 与 `dataease-license-sdk`。

企业版前端页面（`core-frontend/src/views/menu/**`、`views/pages/menu/**`、`views/component/**`）在 V3.1.0 已随社区版开源，企业版后端按前端契约与 `sdk/api` 中的接口自研实现。

## 1. 模块总览

| 位置 | 说明 |
| --- | --- |
| `sdk/foundation` | 开源基础包，替代闭源 `dataease-license-sdk`：`LicenseUtil`、`@XpackInteract`/`@XpackResource`、`V3UserUtil`（当前身份）、插件契约与插件注册表、`DEException`/`ResultCode`/`ResultMessage` |
| `core/core-backend/src/main/java/io/dataease/ee` | 企业版后端 |
| `core/core-backend/src/main/resources/i18n/xpack*.properties` | 企业版国际化资源（unicode 转义） |

`io.dataease.ee` 子包：

| 子包 | 内容 |
| --- | --- |
| `license` | 企业功能开关（`dataease.ee.enabled`，默认 `true`）、`/xpackModel` |
| `interact` | `@XpackInteract` 扩展切面与 `@EeExtension` |
| `auth` | 身份识别过滤器、登录、token、MFA、密码 |
| `permissions` | 组织、角色、用户、菜单、资源登记、权限计算、权限配置 |
| `setting` | 系统参数读写、`perSetting` 扩展（基础设置企业版项、默认登录、外观 UI 列表、导出模式）、外观设置、邮件设置与发送（`EeMailSender`）、MFA/HMAC 设置与 HMAC 校验过滤器、API Key、嵌入式应用与嵌入式 token |
| `plugin` | 插件管理 |
| `init` | 初始化与升级数据（版本组 3） |

## 2. 关键约定

### 2.1 组织与身份
* 单组织模型：每个用户至多属于一个组织（`per_user.default_oid`）。角色属于组织，用户通过 `per_user_role` 拥有组织内角色。
* 系统管理员 uid=1；默认组织 oid=1（不可删除）。
* 系统管理员“进入组织”：前端请求头 `X-DE-ADMIN-PROXY` 携带 RSA 加密的组织 ID，此时 `V3UserUtil.getOid()` 为被代理组织。
* 当前身份统一通过 `V3UserUtil` 获取：`getUid()`、`getOid()`、`getUser()`、`isSysAdmin()`、`getProxy()`。异步任务需显式 `setContext/setUid/setProxy` 并在 `finally` 中 `clear()`。

### 2.2 权限模型
* 内置角色（每个组织三个，`root=true`）：组织管理员(9) 拥有组织内全部权限；数据分析师(7) 对组织内资源有管理权限；普通用户(0) 对组织内资源有查看（数据源为使用）权限。
* 显式授权表 `per_auth`：主体（0 用户 / 1 角色）× 资源类型（0 菜单、1 仪表板、2 数据大屏、3 数据集、4 数据源、8 数据填报、10 电子表格）× 资源 ID → `weight`/`ext`。
* `weight`：1 查看/使用、2 使用（数据源）、4 导出、7 管理、9 授权；`ext` 十进制各位依次为资源导出、数据导出、明细导出（如 111）。
* 资源 ID `0` 为根目录，对其有管理权限才能在根目录创建资源。
* 资源所属组织登记在 `per_busi_resource`（社区版实体），由资源树扩展在核心增删改后同步。
* 权限计算：`EePermissionManage.effective(uid, oid, rtId[, resourceId])` 返回 `Perm`，`Perm.satisfies(level)` 判断等级。
* 接口鉴权：SDK 接口上的 `@DePermit` 由 `DePermitInterceptor` 执行（`m:read` 菜单权限、`<id>:read|manage|export|...` 资源权限，资源类型取 `busiFlag` 或接口上的 `@DeApiPath(rt)`）。

### 2.3 菜单
* 企业版菜单不落库，定义在 `EeMenuRegistry`，由 `menuApi` 扩展在查询时合并进核心菜单并按权限裁剪。
* 组件路径对应 `core-frontend/src/views/pages` 下的页面（`plugin=true` 时为 `views/pages${component}.vue`）。
* “系统设置”(15) 仅系统管理员；“组织管理中心”(7) 仅组织管理员（或被授予）。

### 2.4 扩展核心行为（@XpackInteract）
核心中 `@XpackInteract("name")` 标记的方法由 `XpackInteractAspect` 委托给 `@EeExtension("name")` 标注的 Bean，扩展方法与原方法同名：

| 注解参数 | 扩展方法签名 | 行为 |
| --- | --- | --- |
| `replace=true` | 与原方法相同；或首参为 `EeInvocation` | 返回值替换原方法（可通过 `EeInvocation.proceed()` 调用原方法） |
| `recursion=true` | 首参 `EeInvocation` + 原参数 | 同上 |
| `before=false` / `original=true` | 原参数，可追加一个接收原返回值的参数 | 先执行原方法再调用扩展，扩展返回非空兼容值时替换结果 |
| 默认 | 原参数 | 先调用扩展（可修改入参），再执行原方法 |

### 2.5 开发规范
* 遵循仓库 `AGENTS.md` / `CLAUDE.md`：JPA + QueryDSL、`jakarta.*`、实体 `@Getter/@Setter` 与 `@Comment`、ID 用 `IDUtils.snowID()`、多数据库兼容。
* `JpaUpdateNonNullAspect` 只合并非空字段：清空字段请用 QueryDSL `update ... setNull`。
* 分层：`server`（实现 SDK 接口，`@RestController("<名称>Server")`）→ `manage`（业务逻辑）→ `dao`。
* 新表使用 JPA 实体，`ddl-auto: update` 自动建表；需要初始化或迁移数据时实现 `EeSqlBlock`（版本号以 `3.` 开头，递增）。
* 所有 Long ID 输出为字符串（`@JsonSerialize(using = ToStringSerializer.class)`，SDK VO 已处理）；成功响应 `msg` 为空。
* 业务错误抛 `DEException`，提示文案放入 `i18n/xpack*.properties`（`Translator.get(key, args)`，占位符 `{0}`，非 ASCII 字符用 `\uXXXX`）。
* `core/core-backend` 被根 `.gitignore` 忽略，新增文件需 `git add -f`。

## 3. 构建与运行

```bash
mvn -B install -DskipTests                                  # SDK（含 foundation）
mvn -B -f core/core-backend/pom.xml compile -Pstandalone    # 后端编译
mvn -B -f core/core-backend/pom.xml package -Pstandalone -DskipTests
java -jar core/core-backend/target/CoreApplication.jar
```

* `standalone` 构建排除社区版替补实现 `io/dataease/substitute/**`；`desktop` 构建不包含企业版 `io/dataease/ee/**`。
* 依赖 `repository.fit2cloud.com` 中的定制 Calcite（`calcite-core:1.35.36:de`）。
* 默认管理员：`admin`，初始密码沿用社区版 `substitule.json` 中的密码，否则为 `dataease.default-pwd` 或 `DataEase@123456`。

## 4. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `dataease.ee.enabled` | `true` | 是否启用企业功能 |
| `dataease.ee.corporation` | `自研企业版` | “关于”页面展示的授权对象 |
| `dataease.login_timeout` | `480`（分钟） | 登录有效期 |
| `dataease.default-pwd` | `DataEase@123456` | 新建用户、重置密码使用的默认密码 |
| `dataease.path.plugin` | `/opt/dataease3.0/data/plugin` | 插件包目录 |
| `dataease.path.appearance` | `/opt/dataease3.0/data/appearance` | 外观设置图片目录（文件名 `<雪花ID>.<扩展名>`） |
| `dataease.appearance.max-image-size` | `10485760` | 外观图片大小上限（字节） |
| `dataease.embedded.limit` | `0` | 嵌入式应用数量上限，0 不限 |
| `dataease.embedded.origin-check` | `true` | 嵌入式 token 跨站请求是否校验 `Origin` 在应用域名内 |

## 4.1 外部调用凭证

### API Key（`X-DE-ASK-TOKEN`）
个人中心生成 API Key（每个用户最多 5 个，`accessKey` 16 位、`accessSecret` 32 位）。调用接口时在请求头携带
`X-DE-ASK-TOKEN: <JWT>`：

* JWT 使用 HS256，以 `accessSecret` 为签名密钥；
* 载荷必须包含 `accessKey`，建议携带 `exp`（秒，携带时校验过期，推荐几分钟）；
* 以该 Key 所属用户及其当前组织身份访问，权限与该用户一致；Key 停用、删除或用户被禁用后立即失效；
* 以 API Key 身份不能管理 API Key；HMAC 签名校验对 API Key 请求不生效（JWT 已签名）。

```python
import jwt, time, requests  # PyJWT
token = jwt.encode({"accessKey": AK, "exp": int(time.time()) + 300}, SK, algorithm="HS256")
requests.get("http://host:8100/de2api/user/info", headers={"X-DE-ASK-TOKEN": token})
```

### 嵌入式 token（`X-EMBEDDED-TOKEN`）
宿主系统生成 HS256 JWT，以嵌入式应用的 `appSecret` 签名，声明 `appId`（应用）、`account`（以哪个 DataEase 账号访问，
兼容 `user`），可选 `exp`；其余声明作为外部参数由 `/embedded/getTokenArgs` 返回（保留声明 `appId, account, user,
uid, exp, iat, nbf, iss, sub, aud, jti` 除外）。优先级：公共链接 token > 嵌入式 token > API Key > 登录 token。
重置或因密钥长度变化重新生成密钥后，旧 token 失效；`/embedded/logout` 注销当前 token（单节点内存）。
`/embedded/initIframe` 校验 iframe 父页面来源（RSA 加密的 base64 origin）在应用域名（逗号分隔，按 scheme://host[:port] 比较）内。

### HMAC 请求签名
`hmac.enable=true` 时，`/de2api` 下非白名单请求必须携带
`X-Date: <RFC1123 GMT>` 与 `Authorization: Signature keyId="dataease-key",algorithm="hmac-sha256",headers="@request-target X-Date",signature="<base64(HMAC-SHA256(secret, "dataease-key\n<METHOD> <path>\nX-Date: <date>\n"))>"`，
`|now - X-Date| ≤ hmac.clockSkew` 秒。`path` 为请求 URI（含上下文路径与 `/de2api`、查询串，兼容 axios 查询串编码、绝对地址与
casbi/oidcbi 前缀）。白名单（`WhitelistUtils`）、OPTIONS、非 `/de2api` 请求与 API Key 请求不校验；失败返回 HTTP 401。

## 5. 插件包约定

* `plugin/extensions*.json`：描述文件，至少包含 `moduleName`、`name`、`version`、`config`（可选 `className` 指定入口类）。
* `plugin/{moduleName}.svg`：图标。
* `static/{moduleName}.js`：前端入口，定义 `window[moduleName] = { mapping: { "<base64(moduleName/路径)>": {default: VueComponent} } }`。
* 入口类实现 `DataEaseDatasourcePlugin` / `DataEaseChartPlugin` / `DataFillingPlugin` / `DataEaseSyncDatasourcePlugin`。
