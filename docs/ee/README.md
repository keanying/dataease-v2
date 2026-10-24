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
| `setting` | 系统参数读写 |
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

## 5. 插件包约定

* `plugin/extensions*.json`：描述文件，至少包含 `moduleName`、`name`、`version`、`config`（可选 `className` 指定入口类）。
* `plugin/{moduleName}.svg`：图标。
* `static/{moduleName}.js`：前端入口，定义 `window[moduleName] = { mapping: { "<base64(moduleName/路径)>": {default: VueComponent} } }`。
* 入口类实现 `DataEaseDatasourcePlugin` / `DataEaseChartPlugin` / `DataFillingPlugin` / `DataEaseSyncDatasourcePlugin`。

## 6. 认证集成与平台对接（`io.dataease.ee.sso`、`io.dataease.ee.platform`）

配置统一存放在 `ee_auth_config`（name ∈ ldap/oidc/cas/oauth2/saml2/dingtalk/lark/larksuite/wecom，内容对称加密）。
外部身份通过 `EeSsoUserManage` 对应到用户：先查绑定关系 `per_user_platform(origin, platform_uid)`，账号类来源（LDAP/OIDC/CAS/OAuth2/SAML2）再按“同来源同账号”匹配，
否则按 `basic.autoCreateUser`、`basic.platformOid`（默认 1）、`basic.platformRid`（为空时取组织内置普通用户角色）自动创建。
外部身份**不会**匹配来源不同的同名本地账号（防止 IdP 中的 `admin` 冒用本地管理员），此时提示账号冲突。

| 方式 | 流程 |
| --- | --- |
| LDAP | `/login/localLogin` origin=1：管理员 DN 绑定 → 各 OU（`|` 分隔）按过滤器查找 → 用户 DN + 密码绑定；空密码直接拒绝；每次登录同步姓名、邮箱；不走 MFA（前端无 MFA 步骤） |
| 钉钉/飞书/国际飞书/企业微信 | `/{p}/token` 用授权码换取 5 分钟、一次性的临时登录票据（独立签名，不能当登录 token 用），前端以 X-DE-TOKEN 调用 `/login/platformLogin/{origin}` 换取正式 token；`/{p}/bind` 绑定当前用户 |
| OAuth2 | `/oauth2/auth` 返回签名 state（含 `fit2clouddeoauth2`，5 分钟、一次性）；`/oauth2/token` 换取 access_token → userinfo → 临时票据；`authMethod` 1 为 client_secret_jwt；`mapping` 支持 `a.b.c` 路径，可选 `codeKey` |
| SAML2 | 根路径 `/saml/login`、`/saml/sso`、`/saml/metadata`（`@Controller`，不加 /de2api）；IdP 元数据保存时解析；AuthnRequest 支持 Redirect（查询串签名）与 POST（封装签名）；Response 必须有有效签名并校验 Destination/InResponseTo/Issuer/时间/Audience/Recipient，成功后回跳 `?state=fit2cloud-saml2&saml2Token=…`；不支持加密断言、默认拒绝 IdP 发起的登录（`dataease.ee.sso.saml.allow-idp-initiated`） |
| OIDC / CAS | 前端跳转 `/oidcbi/`、`/casbi/` 后调用 `platformLogin(2/3)`，身份来源见下 |

### OIDC / CAS 的两种部署方式

1. **APISIX 网关（安装包 enterprise 模式）**：保存或启停 OIDC/CAS 时，后端通过 Admin API（`dataease.apisix-api.domain/key`）维护路由
   `dataease`（`/*`）、`dataease-oidc`（`/oidcbi/*`，openid-connect 插件，`logout_path=/oidcbi/oidc/logout`）、`dataease-cas`（`/casbi/*`，cas-auth 插件，
   回调 `/casbi/cas_callback`、登出 `/casbi/cas/logout`），并去掉前缀、注入共享密钥头 `X-DE-GATEWAY-SECRET`。后端只信任携带正确密钥的
   `X-Userinfo`（Base64 JSON）/ `X-CAS-USER`。网关不可用时只记录警告。用户需通过 APISIX 端口访问整个站点。
2. **内置网关（无 APISIX，直接访问后端端口）**：`EeSsoGatewayFilter` 处理 `/oidcbi/**`、`/casbi/**`：去掉前缀后交给后续过滤器；页面请求未登录时
   跳转 IdP（OIDC 授权码 + 可选 PKCE，state/nonce 存签名 Cookie；CAS `/login?service=…/casbi/cas_callback`，`/p3/serviceValidate` 校验 ticket），
   认证后以签名 Cookie `DE_SSO_SESSION`（Path 限定在前缀下）保存会话；同样处理两个登出地址。OIDC 的 `redirectUri` 必须位于 `/oidcbi/` 下。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `dataease.ee.sso.gateway-secret` | 由 APISIX key 派生 | 网关注入的共享密钥；使用自建网关（nginx 等）时设置此值并让网关注入 `X-DE-GATEWAY-SECRET` |
| `dataease.ee.sso.gateway-trust` | `secret` | `any`：信任任何请求的身份头（仅限网关会剥离客户端同名头的部署）；`none`：只用内置网关 |
| `dataease.ee.sso.native-enabled` | `true` | 是否启用内置网关 |
| `dataease.ee.sso.apisix.enabled` / `.upstream` | `true` / `dataease:8100` | 是否维护 APISIX 路由及上游地址 |
| `dataease.ee.sso.trust-all-certificates` | `false` | 调用 IdP / 开放平台时是否信任自签名证书 |
| `dataease.ee.platform.{dingtalk.oapi,dingtalk.api,lark.api,larksuite.api,wecom.api}` | 官方域名 | 开放平台地址（私有化部署或测试替身） |

### 供其他模块使用
* `EePlatformMessageService.sendToUsers(origin, uids, message)` / `sendToGroups(origin, groupIds, message)`；`EePlatformMessage.text/markdown/image/file`。
  每个平台一个 `EePlatformMessageSender` Bean（钉钉：工作通知 + 群机器人 `openConversationId`；飞书：`im/v1/messages`，群为 `chat_id`；企业微信：应用消息 + 应用群聊）。
* `EeAuthenticationManage.defaultLogin()`：`basic.defaultLogin`（0/1/2/3/9/10）映射为前端需要的 0..5，所选方式未启用时为 0，供 `/sysParameter/defaultLogin` 扩展调用。
* `@EeExtension("larkManage")`：`getTenantAccessToken()` 返回飞书（未配置时国际飞书）的 tenant_access_token。
