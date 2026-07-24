/*
 * 测试库备份 / 清空 / 还原工具。
 *
 * <p>「清空但保留数据」的安全实现：对给定表做 <code>CREATE TABLE bak AS SELECT *</code> 备份，
 * 再 <code>DELETE</code> 清空；测试结束后把备份数据 <code>INSERT</code> 回去并 <code>DROP</code> 备份表。
 * 这样跑集成测试绝不会永久丢失本地数据。
 *
 * <p>表名必须匹配 <code>[A-Za-z0-9_]+</code>（防注入）。不存在的表静默跳过（如 pay_app_config
 * 不在本 schema 时），并打印告警。
 */
package org.apache.shenyu.admin.custom;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试库备份 / 还原工具（测试专用）。
 */
public final class DbBackupRestore {

    private DbBackupRestore() {
    }

    /**
     * 备份并清空给定表。返回 表名→备份表名 的映射（仅成功备份的表）。
     *
     * @param jdbcTemplate JDBC 模板
     * @param tables       待清空并保留的表名列表
     * @return 备份映射，测试结束后传给 {@link #restoreAndDrop}
     */
    public static Map<String, String> backupAndClear(final JdbcTemplate jdbcTemplate, final List<String> tables) {
        final Map<String, String> backups = new LinkedHashMap<>();
        for (final String table : tables) {
            if (table == null || !table.matches("[A-Za-z0-9_]+")) {
                System.out.println("[DbBackupRestore] 跳过非法表名: " + table);
                continue;
            }
            final String bak = "bak_" + table + "_" + System.nanoTime();
            try {
                jdbcTemplate.update("CREATE TABLE " + bak + " AS SELECT * FROM " + table);
                jdbcTemplate.update("DELETE FROM " + table);
                backups.put(table, bak);
                System.out.println("[DbBackupRestore] 已备份并清空表: " + table + " -> " + bak);
            } catch (final DataAccessException e) {
                System.out.println("[DbBackupRestore] 表不存在或备份失败，跳过: " + table
                        + " (" + e.getMessage() + ")");
            }
        }
        return backups;
    }

    /**
     * 还原并删除备份表。清空当前表后把备份数据写回。
     *
     * @param jdbcTemplate JDBC 模板
     * @param backups      {@link #backupAndClear} 返回的映射
     */
    public static void restoreAndDrop(final JdbcTemplate jdbcTemplate, final Map<String, String> backups) {
        for (final Map.Entry<String, String> entry : backups.entrySet()) {
            final String table = entry.getKey();
            final String bak = entry.getValue();
            try {
                jdbcTemplate.update("DELETE FROM " + table);
                jdbcTemplate.update("INSERT INTO " + table + " SELECT * FROM " + bak);
                jdbcTemplate.update("DROP TABLE " + bak);
                System.out.println("[DbBackupRestore] 已还原表: " + table + " (备份 " + bak + " 已删除)");
            } catch (final DataAccessException e) {
                System.out.println("[DbBackupRestore] 还原失败，请人工检查备份表 " + bak + ": " + e.getMessage());
            }
        }
    }
}
