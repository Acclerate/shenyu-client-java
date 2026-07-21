-- ============================================================
-- sign SPI 配置：通过 springCloud 插件的 plugin.config 下发
-- 玩法 A：每个配置项一个独立 plugin_handle 字段（type=3 = 插件级）
-- 读取侧：网关 SPI 用 BaseDataCache.obtainPluginData("springCloud").getConfig()
--          （见 HttpBizPublicKeyProvider.syncAdminConfig()）
--
-- ⚠️ 2.6.1 plugin_handle 实际列结构（已 DESC 确认）：
--    id, plugin_id, field, label, data_type, type, sort, ext_obj, date_created, date_updated
--    —— 没有独立列 required/default_value/placeholder/rule，这些元数据统一塞进 ext_obj(JSON)
--    ext_obj 格式样例：{"required":"0","defaultValue":"http","placeholder":"classpath / http","rule":""}
--    ⚠️ plugin_id 必须是【数值型插件id】(springCloud=8)，绝不能写字符串 'springCloud'！
--       本列是 VARCHAR，写字符串会被原样存成 'springCloud'，admin API 按 pluginId=8 过滤时查不到（踩过的坑）。
--       另外 (plugin_id, field, type) 有【大小写不敏感唯一索引】，手动加的 'gw.springcloud.x' 会与 'gw.springcloud.x' 撞唯一键。
--
-- 前提：
--   1. springCloud 插件已启用（你们有 springcloud-demo 走网关，应已启用）
--   2. sign SPI jar 已部署到 ext-lib 并完成本改造
--
-- 幂等：INSERT ... ON DUPLICATE KEY UPDATE，可重复执行不报错。
-- ============================================================

-- 1) 插件处理管理：给 springCloud 增加 sign SPI 的配置字段
--    data_type: 1=数字 2=字符串 3=下拉框  → 全部用 2(字符串)，SPI 侧自行解析
--    type:      1=选择器 2=规则 3=插件    → 全部用 3(插件级)
--    ext_obj.required:    "0"=否 "1"=是
--    ext_obj.defaultValue: 新建时自动填入
--    ext_obj.placeholder:  输入框提示
--    ext_obj.rule:         前端正则校验（留空=不校验）
INSERT INTO plugin_handle
  (id, plugin_id, field, label, data_type, type, sort, ext_obj)
VALUES
  (2079990100000000001, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.key-source',                          '公钥源模式',         2, 3, 1, '{"required":"0","defaultValue":"http","placeholder":"classpath / http","rule":""}'),
  (2079990100000000002, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.classpath-public-key',                'classpath公钥路径',  2, 3, 2, '{"required":"0","defaultValue":"biz-public-key.pem","placeholder":"classpath PEM 文件名","rule":""}'),
  (2079990100000000003, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.http.base-url',                       '公钥服务地址',       2, 3, 3, '{"required":"0","defaultValue":"http://127.0.0.1:8470","placeholder":"demo 公钥接口根地址","rule":""}'),
  (2079990100000000004, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.http.path-pattern',                   '公钥路径模板',       2, 3, 4, '{"required":"0","defaultValue":"/sign/public-key/%s","placeholder":"含 %s 占位 appKey","rule":""}'),
  (2079990100000000005, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.http.connect-timeout-ms',             '连接超时(ms)',       2, 3, 5, '{"required":"0","defaultValue":"1000","placeholder":"连接超时毫秒","rule":"^[0-9]+$"}'),
  (2079990100000000006, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.http.read-timeout-ms',                '读超时(ms)',         2, 3, 6, '{"required":"0","defaultValue":"2000","placeholder":"读超时毫秒","rule":"^[0-9]+$"}'),
  (2079990100000000007, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.http.max-connections',                '连接池大小',         2, 3, 7, '{"required":"0","defaultValue":"20","placeholder":"最大连接数","rule":"^[0-9]+$"}'),
  (2079990100000000008, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.http.refresh-interval-seconds',       '刷新间隔(秒)',       2, 3, 8, '{"required":"0","defaultValue":"15","placeholder":"后台刷新周期","rule":"^[0-9]+$"}'),
  (2079990100000000009, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.cache.ttl-seconds',                   '缓存TTL(秒)',        2, 3, 9, '{"required":"0","defaultValue":"30","placeholder":"公钥缓存时长","rule":"^[0-9]+$"}'),
  (2079990100000000010, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.cache.failure-retry-seconds',         '失败重试(秒)',       2, 3, 10, '{"required":"0","defaultValue":"3","placeholder":"刷新失败重试","rule":"^[0-9]+$"}'),
  (2079990100000000011, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.cache.allow-stale-on-refresh-failure', '允许过期缓存',       2, 3, 11, '{"required":"0","defaultValue":"true","placeholder":"true/false","rule":"^(true|false)$"}'),
  (2079990100000000012, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.pre-warm-app-keys',                   '预热appKey列表',     2, 3, 12, '{"required":"0","defaultValue":"biz001,biz002","placeholder":"逗号分隔","rule":""}'),
  (2079990100000000013, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.app-key.biz001',                      'biz001静态公钥',     2, 3, 13, '{"required":"0","defaultValue":"","placeholder":"整段 PEM（可选兜底）","rule":""}'),
  (2079990100000000014, (SELECT id FROM plugin WHERE name='springCloud'), 'gw.springcloud.app-key.biz002',                      'biz002静态公钥',     2, 3, 14, '{"required":"0","defaultValue":"","placeholder":"整段 PEM（可选兜底）","rule":""}')
ON DUPLICATE KEY UPDATE
  plugin_id = VALUES(plugin_id),
  field     = VALUES(field),
  label     = VALUES(label),
  data_type = VALUES(data_type),
  type      = VALUES(type),
  sort      = VALUES(sort),
  ext_obj   = VALUES(ext_obj);

-- 2) 插件管理 → 编辑 springCloud → 配置：填入实际值
--    若 UI 已能渲染上面字段，直接在各输入框填值即可；
--    否则用下面 UPDATE 直写 springCloud 的 config（JSON 扁平 key，与 SPI 读取对应）
UPDATE plugin
SET config = '{"gw.springcloud.key-source":"http","gw.springcloud.classpath-public-key":"biz-public-key.pem","gw.springcloud.http.base-url":"http://127.0.0.1:8470","gw.springcloud.http.path-pattern":"/sign/public-key/%s","gw.springcloud.http.connect-timeout-ms":"1000","gw.springcloud.http.read-timeout-ms":"2000","gw.springcloud.http.max-connections":"20","gw.springcloud.http.refresh-interval-seconds":"15","gw.springcloud.cache.ttl-seconds":"30","gw.springcloud.cache.failure-retry-seconds":"3","gw.springcloud.cache.allow-stale-on-refresh-failure":"true","gw.springcloud.pre-warm-app-keys":"biz001,biz002"}'
WHERE name = 'springCloud';

-- 3) 改完重启 gateway（不是 admin），config 经 websocket 进 BaseDataCache，
--    SPI 守护线程下一个刷新周期（≤15s）自动读到并热生效，无需 force-recreate。
