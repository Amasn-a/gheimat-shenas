package com.digikala.supercoin.gamification.games.priceexpert.infrastructure.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Minimal JDBC helper bound to the current transaction's connection (see {@link JdbcTransactionRunner}). */
public final class Sql {
    private Sql() {}

    @FunctionalInterface public interface Row<T> { T map(ResultSet rs) throws SQLException; }

    /** Typed null for parameters whose SQL type cannot be inferred. */
    public record Null(int sqlType) {}
    public static final Null NULL_TEXT = new Null(Types.VARCHAR);
    public static final Null NULL_TS = new Null(Types.TIMESTAMP_WITH_TIMEZONE);
    public static final Null NULL_INT = new Null(Types.INTEGER);
    public static final Null NULL_BIGINT = new Null(Types.BIGINT);
    public static final Null NULL_BOOL = new Null(Types.BOOLEAN);
    public static final Null NULL_DATE = new Null(Types.DATE);

    /** Marker for a bigint[] parameter. */
    public record BigintArray(Collection<Long> values) {}

    static Connection conn() { return JdbcTransactionRunner.current(); }

    public static <T> List<T> query(String sql, Row<T> row, Object... args) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(row.map(rs));
                return out;
            }
        } catch (SQLException e) { throw new DataAccessException(sql, e); }
    }

    public static <T> Optional<T> one(String sql, Row<T> row, Object... args) {
        List<T> l = query(sql, row, args);
        if (l.size() > 1) throw new IllegalStateException("expected at most one row: " + sql);
        return l.isEmpty() ? Optional.empty() : Optional.of(l.get(0));
    }

    public static int update(String sql, Object... args) {
        try (PreparedStatement ps = conn().prepareStatement(sql)) {
            bind(ps, args);
            return ps.executeUpdate();
        } catch (SQLException e) { throw new DataAccessException(sql, e); }
    }

    static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            int idx = i + 1;
            if (a == null) ps.setObject(idx, null);
            else if (a instanceof Null n) ps.setNull(idx, n.sqlType());
            else if (a instanceof Instant t) ps.setObject(idx, OffsetDateTime.ofInstant(t, ZoneOffset.UTC));
            else if (a instanceof LocalDate d) ps.setObject(idx, d);
            else if (a instanceof Enum<?> e) ps.setString(idx, e.name());
            else if (a instanceof BigintArray arr) {
                Array sqlArr = ps.getConnection().createArrayOf("bigint", arr.values().toArray(new Long[0]));
                ps.setArray(idx, sqlArr);
            } else ps.setObject(idx, a);
        }
    }

    public static Instant ts(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }

    public static LocalDate date(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, LocalDate.class);
    }

    public static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    public static Long longOrNull(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public static Boolean boolOrNull(ResultSet rs, String col) throws SQLException {
        boolean v = rs.getBoolean(col);
        return rs.wasNull() ? null : v;
    }

    public static Object nz(Instant t) { return t == null ? NULL_TS : t; }
    public static Object nz(String s) { return s == null ? NULL_TEXT : s; }
    public static Object nz(Integer v) { return v == null ? NULL_INT : v; }
    public static Object nz(Long v) { return v == null ? NULL_BIGINT : v; }
    public static Object nz(Boolean v) { return v == null ? NULL_BOOL : v; }
    public static Object nz(LocalDate v) { return v == null ? NULL_DATE : v; }
    public static Object nz(Enum<?> v) { return v == null ? NULL_TEXT : v.name(); }

    public static final class DataAccessException extends RuntimeException {
        private final String sqlState;
        public DataAccessException(String sql, SQLException e) {
            super(e.getMessage() + " [" + abbreviate(sql) + "]", e);
            this.sqlState = e.getSQLState();
        }
        public String sqlState() { return sqlState; }
        public boolean isUniqueViolation() { return "23505".equals(sqlState); }
        private static String abbreviate(String s) { return s.length() > 160 ? s.substring(0, 160) + "…" : s; }
    }
}
