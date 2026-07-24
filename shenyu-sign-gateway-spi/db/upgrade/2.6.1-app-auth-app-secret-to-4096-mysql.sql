-- =============================================================================
-- ShenYu 2.6.1 app_auth.app_secret 扩列脚本（MySQL）
-- 用途：将 app_secret 列从 VARCHAR(128) 扩到 VARCHAR(4096)，
--       以承载 RSA 公钥 PEM 文本（RSA-2048 PEM ≈ 450 字符，RSA-4096 PEM ≈ 800 字符）。
--
-- 【背景】shenyu-sign-gateway-spi 自定义验签 SPI 从 app_auth.app_secret 读取 RSA 公钥 PEM
--        做非对称验签（复用该列承载公钥，语义见设计方案 §2.3）。
--        原 VARCHAR(128) 连 RSA-2048 公钥都装不下，必须扩列。
--
-- 【执行前检查】
--   1. 确认操作的是 app_auth 表，勿误改 alert_receiver.app_secret（告警接收器，无关）。
--   2. 建议在低峰期执行（ALTER 会重建表，MySQL 8.0+ 对 VARCHAR 长度变更通常是 INSTANT，
--      但 5.7 可能 COPY；提前评估锁影响）。
--
-- 【回滚】（仅当彻底复原时）
--   回滚前必须确保所有 app_secret 值 ≤ 128 字符（即已清空所有 PEM 公钥），否则截断：
--   ALTER TABLE `app_auth` MODIFY COLUMN `app_secret` VARCHAR(128)
--     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL;
-- =============================================================================

ALTER TABLE `app_auth`
  MODIFY COLUMN `app_secret` VARCHAR(4096)
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
  NOT NULL COMMENT '验签凭证（本方案中承载 RSA 公钥 PEM；原生语义为 HMAC 对称密钥）';
