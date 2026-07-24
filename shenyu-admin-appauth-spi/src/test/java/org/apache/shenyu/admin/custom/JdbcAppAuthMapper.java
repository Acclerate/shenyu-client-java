/*
 * 测试用 AppAuthMapper JDBC 实现。
 *
 * <p>shenyu-admin 的 AppAuthMapper 绑定 XML 未打进发布 jar，无法在独立集成测试中复用。
 * 本类用 JdbcTemplate 直连真实 app_auth 表，实现生产路径真正用到的三个方法
 * （findByAppKey / insertSelective / updateSelective），其余接口方法给空实现（测试不触发）。
 *
 * <p>SQL 语义与 shenyu 原生 mapper 一致：insertSelective 全字段写入（id/appKey/appSecret/
 * userId/open/enabled/时间戳）；updateSelective 的 SET 子句<b>不含 app_key</b>（appKey 不可变），
 * 只更新 appSecret/open/enabled/date_updated。
 */
package org.apache.shenyu.admin.custom;

import org.apache.shenyu.admin.mapper.AppAuthMapper;
import org.apache.shenyu.admin.model.entity.AppAuthDO;
import org.apache.shenyu.admin.model.query.AppAuthQuery;
import org.apache.shenyu.admin.model.vo.AppAuthVO;
import org.apache.shenyu.admin.validation.ExistProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.dao.EmptyResultDataAccessException;

import java.io.Serializable;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Collections;
import java.util.List;

/**
 * 真实 JDBC 版 AppAuthMapper（测试专用）。
 */
public class JdbcAppAuthMapper implements AppAuthMapper {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAppAuthMapper(final JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AppAuthDO> ROW_MAPPER = (ResultSet rs, int rowNum) -> AppAuthDO.builder()
            .id(rs.getString("id"))
            .appKey(rs.getString("app_key"))
            .appSecret(rs.getString("app_secret"))
            .userId(rs.getString("user_id"))
            .phone(rs.getString("phone"))
            .extInfo(rs.getString("ext_info"))
            .open(rs.getBoolean("open"))
            .enabled(rs.getBoolean("enabled"))
            .dateCreated(rs.getTimestamp("date_created"))
            .dateUpdated(rs.getTimestamp("date_updated"))
            .build();

    @Override
    public AppAuthDO findByAppKey(final String appKey) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, app_key, app_secret, user_id, phone, ext_info, open, enabled, "
                            + "date_created, date_updated FROM app_auth WHERE app_key = ?",
                    ROW_MAPPER, appKey);
        } catch (final EmptyResultDataAccessException e) {
            return null;
        }
    }

    @Override
    public int insertSelective(final AppAuthDO appAuthDO) {
        return jdbcTemplate.update(
                "INSERT INTO app_auth (id, app_key, app_secret, user_id, open, enabled, "
                        + "date_created, date_updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                appAuthDO.getId(), appAuthDO.getAppKey(), appAuthDO.getAppSecret(),
                appAuthDO.getUserId(), appAuthDO.getOpen(), appAuthDO.getEnabled(),
                appAuthDO.getDateCreated(), appAuthDO.getDateUpdated());
    }

    @Override
    public int updateSelective(final AppAuthDO appAuthDO) {
        // SET 子句不含 app_key：appKey 不可变
        return jdbcTemplate.update(
                "UPDATE app_auth SET app_secret = ?, open = ?, enabled = ?, date_updated = ? WHERE id = ?",
                appAuthDO.getAppSecret(), appAuthDO.getOpen(), appAuthDO.getEnabled(),
                appAuthDO.getDateUpdated(), appAuthDO.getId());
    }

    // ----------------- 以下为接口占位实现（测试不触发，给安全默认值） -----------------

    @Override
    public Boolean existed(final Serializable id) {
        return Boolean.FALSE;
    }

    @Override
    public Boolean appKeyExisted(final Serializable appKey) {
        return Boolean.FALSE;
    }

    @Override
    public AppAuthDO selectById(final String id) {
        return null;
    }

    @Override
    public List<AppAuthDO> selectByIds(final List<String> ids) {
        return Collections.emptyList();
    }

    @Override
    public List<AppAuthDO> selectByQuery(final AppAuthQuery appAuthQuery) {
        return Collections.emptyList();
    }

    @Override
    public List<AppAuthDO> selectAll() {
        return Collections.emptyList();
    }

    @Override
    public Integer countByQuery(final AppAuthQuery appAuthQuery) {
        return 0;
    }

    @Override
    public int insert(final AppAuthDO appAuthDO) {
        return insertSelective(appAuthDO);
    }

    @Override
    public int update(final AppAuthDO appAuthDO) {
        return updateSelective(appAuthDO);
    }

    @Override
    public int updateEnable(final AppAuthDO appAuthDO) {
        return 0;
    }

    @Override
    public int updateEnableBatch(final List<String> idList, final Boolean enabled) {
        return 0;
    }

    @Override
    public int updateAppSecretByAppKey(final String appKey, final String appSecret) {
        return 0;
    }

    @Override
    public int delete(final String id) {
        return 0;
    }

    @Override
    public int deleteByIds(final List<String> ids) {
        return 0;
    }

    @Override
    public List<AppAuthVO> selectByCondition(final AppAuthQuery condition) {
        return Collections.emptyList();
    }
}
