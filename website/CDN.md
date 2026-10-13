# 官网直读版本清单

官网只读取 <https://888413.xyz/apk/latest.json>，不在源码或部署产物中维护另一份清单。每次打开官网、切回标签页及页面可见时每 5 分钟，重新请求源清单。

## 云 CDN / 对象存储设置

只针对 `/apk/latest.json` 配置：

| 配置项 | 值 |
| --- | --- |
| 响应头 `Access-Control-Allow-Origin` | `*` |
| 响应头 `Cache-Control` | `no-store` |
| CDN 边缘缓存 | 不缓存该路径 |

JSON 是公开资源，官网请求不携带 Cookie 或认证信息。若通过对象存储的 CORS 规则配置，可把允许来源设为 `*`、允许方法设为 `GET` / `HEAD`。官网不发送自定义请求头，不需要凭据或额外的预检接口。若 CDN 覆盖了源站响应头，也需在 CDN 的响应头规则中保留上述配置。

规则生效后，刷新 `/apk/latest.json` 的 CDN 缓存。每次发布仍然只更新现有源清单；APK 和模型等其他路径可以继续使用原有缓存规则。

## 检查配置

```sh
curl --fail --silent --show-error --include \
  -H 'Origin: https://ld0574.github.io' \
  https://888413.xyz/apk/latest.json
```

返回状态应为 `200`，并包含 `Access-Control-Allow-Origin: *`。然后刷新 <https://ld0574.github.io/SenseField/>，确认两处下载地址、安装包大小与更新说明均来自这份 JSON。

如果跨域配置尚未生效，浏览器会阻止读取：官网显示「暂时无法读取版本信息，可前往发行页面下载。」，下载按钮转到码云发行页，不会显示之前保存的版本。
