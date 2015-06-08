/*
 * Copyright (c) 2006-2011 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Florent Guillaume
 *     Benoit Delbosc
 */

package org.nuxeo.ecm.core.storage.sql.jdbc.dialect;

import java.io.Serializable;
import java.net.SocketException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.common.utils.StringUtils;
import org.nuxeo.ecm.core.NXCore;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.ecm.core.storage.FulltextConfiguration;
import org.nuxeo.ecm.core.storage.FulltextQueryAnalyzer;
import org.nuxeo.ecm.core.storage.FulltextQueryAnalyzer.FulltextQuery;
import org.nuxeo.ecm.core.storage.FulltextQueryAnalyzer.Op;
import org.nuxeo.ecm.core.storage.StorageException;
import org.nuxeo.ecm.core.storage.sql.ColumnType;
import org.nuxeo.ecm.core.storage.sql.Model;
import org.nuxeo.ecm.core.storage.sql.RepositoryDescriptor;
import org.nuxeo.ecm.core.storage.sql.jdbc.QueryMaker.QueryMakerException;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Column;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Database;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Join;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.Table;
import org.nuxeo.ecm.core.storage.sql.jdbc.db.TableAlias;

/**
 * PostgreSQL-specific dialect.
 *
 * @author Florent Guillaume
 */
public class DialectPostgreSQL extends Dialect {

    private static final Log log = LogFactory.getLog(DialectPostgreSQL.class);

    private static final String DEFAULT_FULLTEXT_ANALYZER = "english";

    private static final String DEFAULT_USERS_SEPARATOR = ",";

    private static final String PREFIX_SEARCH = ":*";

    // prefix search syntax foo* or foo% or foo:*-> foo:*
    private static final Pattern PREFIX_PATTERN = Pattern.compile("(\\*|%|:\\*)( |\"|$)");

    private static final String PREFIX_REPL = PREFIX_SEARCH + "$2";

    private static final String[] RESERVED_COLUMN_NAMES = { "xmin", "xmax", "cmin", "cmax", "ctid", "oid", "tableoid" };

    private static final String UNLOGGED_KEYWORD = "UNLOGGED";

    protected final String fulltextAnalyzer;

    protected final boolean supportsWith;

    protected boolean hierarchyCreated;

    protected boolean pathOptimizationsEnabled;

    protected String usersSeparator;

    protected final DialectIdType idType;

    protected boolean compatibilityFulltextTable;

    protected final String unloggedKeyword;

    protected String idSequenceName;

    public DialectPostgreSQL(DatabaseMetaData metadata, RepositoryDescriptor repositoryDescriptor)
            throws StorageException {
        super(metadata, repositoryDescriptor);
        fulltextAnalyzer = repositoryDescriptor == null ? null
                : repositoryDescriptor.fulltextAnalyzer == null ? DEFAULT_FULLTEXT_ANALYZER
                        : repositoryDescriptor.fulltextAnalyzer;
        pathOptimizationsEnabled = repositoryDescriptor == null ? false
                : repositoryDescriptor.getPathOptimizationsEnabled();
        int major, minor;
        try {
            major = metadata.getDatabaseMajorVersion();
            minor = metadata.getDatabaseMinorVersion();
        } catch (SQLException e) {
            throw new StorageException(e);
        }
        supportsWith = major > 8 || (major == 8 && minor >= 4);
        if ((major == 9 && minor >= 1) || (major > 9)) {
            unloggedKeyword = UNLOGGED_KEYWORD;
        } else {
            unloggedKeyword = "";
        }
        usersSeparator = repositoryDescriptor == null ? null
                : repositoryDescriptor.usersSeparatorKey == null ? DEFAULT_USERS_SEPARATOR
                        : repositoryDescriptor.usersSeparatorKey;
        String idt = repositoryDescriptor == null ? null : repositoryDescriptor.idType;
        if (idt == null || "".equals(idt) || "varchar".equalsIgnoreCase(idt)) {
            idType = DialectIdType.VARCHAR;
        } else if ("uuid".equalsIgnoreCase(idt)) {
            idType = DialectIdType.UUID;
        } else if (idt.toLowerCase().startsWith("sequence")) {
            idType = DialectIdType.SEQUENCE;
            if (idt.toLowerCase().startsWith("sequence:")) {
                String[] split = idt.split(":");
                idSequenceName = split[1];
            } else {
                idSequenceName = "hierarchy_seq";
            }
        } else {
            throw new StorageException("Unknown id type: '" + idt + "'");
        }
        try {
            compatibilityFulltextTable = getCompatibilityFulltextTable(metadata);
        } catch (SQLException e) {
            throw new StorageException(e);
        }
    }

    protected boolean getCompatibilityFulltextTable(DatabaseMetaData metadata) throws SQLException {
        ResultSet rs = metadata.getColumns(null, null, Model.FULLTEXT_TABLE_NAME, "%");
        while (rs.next()) {
            // COLUMN_NAME=fulltext DATA_TYPE=1111 TYPE_NAME=tsvector
            String columnName = rs.getString("COLUMN_NAME");
            if (Model.FULLTEXT_FULLTEXT_KEY.equals(columnName)) {
                String typeName = rs.getString("TYPE_NAME");
                return "tsvector".equals(typeName);
            }
        }
        return false;
    }

    @Override
    public String toBooleanValueString(boolean bool) {
        return bool ? "true" : "false";
    }

    @Override
    public String getNoColumnsInsertString() {
        return "DEFAULT VALUES";
    }

    @Override
    public String getCascadeDropConstraintsString() {
        return "CASCADE";
    }

    @Override
    public JDBCInfo getJDBCTypeAndString(ColumnType type) {
        switch (type.spec) {
        case STRING:
            if (type.isUnconstrained()) {
                return jdbcInfo("varchar", Types.VARCHAR);
            } else if (type.isClob()) {
                return jdbcInfo("text", Types.CLOB);
            } else {
                return jdbcInfo("varchar(%d)", type.length, Types.VARCHAR);
            }
        case ARRAY_STRING:
            if (type.isUnconstrained()) {
                return jdbcInfo("varchar[]", Types.ARRAY, "varchar", Types.VARCHAR);
            } else if (type.isClob()) {
                return jdbcInfo("text[]", Types.ARRAY, "text", Types.CLOB);
            } else {
                return jdbcInfo("varchar(%d)[]", type.length, Types.ARRAY, "varchar", Types.VARCHAR);
            }
        case BOOLEAN:
            return jdbcInfo("bool", Types.BIT);
        case ARRAY_BOOLEAN:
            return jdbcInfo("bool[]", Types.ARRAY, "bool", Types.BOOLEAN);
        case LONG:
            return jdbcInfo("int8", Types.BIGINT);
        case ARRAY_LONG:
            return jdbcInfo("int8[]", Types.ARRAY, "int8", Types.BIGINT);
        case DOUBLE:
            return jdbcInfo("float8", Types.DOUBLE);
        case ARRAY_DOUBLE:
            return jdbcInfo("float8[]", Types.ARRAY, "float8", Types.DOUBLE);
        case TIMESTAMP:
            return jdbcInfo("timestamp", Types.TIMESTAMP);
        case ARRAY_TIMESTAMP:
            return jdbcInfo("timestamp[]", Types.ARRAY, "timestamp", Types.TIMESTAMP);
        case BLOBID:
            return jdbcInfo("varchar(250)", Types.VARCHAR);
        case ARRAY_BLOBID:
            return jdbcInfo("varchar(250)[]", Types.ARRAY, "varchar", Types.VARCHAR);
            // -----
        case NODEID:
        case NODEIDFK:
        case NODEIDFKNP:
        case NODEIDFKMUL:
        case NODEIDFKNULL:
        case NODEIDPK:
        case NODEVAL:
            switch (idType) {
            case VARCHAR:
                return jdbcInfo("varchar(36)", Types.VARCHAR);
            case UUID:
                return jdbcInfo("uuid", Types.OTHER);
            case SEQUENCE:
                return jdbcInfo("int8", Types.BIGINT);
            }
        case NODEARRAY:
            switch (idType) {
            case VARCHAR:
                return jdbcInfo("varchar(36)[]", Types.ARRAY, "varchar", Types.VARCHAR);
            case UUID:
                return jdbcInfo("uuid[]", Types.ARRAY, "uuid", Types.OTHER);
            case SEQUENCE:
                return jdbcInfo("int8[]", Types.ARRAY, "int8", Types.BIGINT);
            }
        case SYSNAME:
            return jdbcInfo("varchar(250)", Types.VARCHAR);
        case SYSNAMEARRAY:
            return jdbcInfo("varchar(250)[]", Types.ARRAY, "varchar", Types.VARCHAR);
        case TINYINT:
            return jdbcInfo("int2", Types.SMALLINT);
        case INTEGER:
            return jdbcInfo("int4", Types.INTEGER);
        case ARRAY_INTEGER:
            return jdbcInfo("int4[]", Types.ARRAY, "int4", Types.INTEGER);
        case AUTOINC:
            return jdbcInfo("serial", Types.INTEGER);
        case FTINDEXED:
            if (compatibilityFulltextTable) {
                return jdbcInfo("tsvector", Types.OTHER);
            } else {
                return jdbcInfo("text", Types.CLOB);
            }
        case FTSTORED:
            if (compatibilityFulltextTable) {
                return jdbcInfo("tsvector", Types.OTHER);
            } else {
                return jdbcInfo("text", Types.CLOB);
            }
        case CLUSTERNODE:
            return jdbcInfo("int4", Types.INTEGER);
        case CLUSTERFRAGS:
            return jdbcInfo("varchar[]", Types.ARRAY, "varchar", Types.VARCHAR);
        }
        throw new AssertionError(type);
    }

    @Override
    public boolean isAllowedConversion(int expected, int actual, String actualName, int actualSize) {
        // CLOB vs VARCHAR compatibility
        if (expected == Types.VARCHAR && actual == Types.CLOB) {
            return true;
        }
        if (expected == Types.CLOB && actual == Types.VARCHAR) {
            return true;
        }
        // INTEGER vs BIGINT compatibility
        if (expected == Types.BIGINT && actual == Types.INTEGER) {
            return true;
        }
        if (expected == Types.INTEGER && actual == Types.BIGINT) {
            return true;
        }
        // TSVECTOR vs CLOB compatibility during upgrade tests
        // where column detection is done before upgrade test setup
        if (expected == Types.CLOB && (actual == Types.OTHER && actualName.equals("tsvector"))) {
            return true;
        }
        return false;
    }

    @Override
    public Serializable getGeneratedId(Connection connection) throws SQLException {
        if (idType != DialectIdType.SEQUENCE) {
            return super.getGeneratedId(connection);
        }
        String sql = String.format("SELECT NEXTVAL('%s')", idSequenceName);
        Statement s = connection.createStatement();
        try {
            ResultSet rs = s.executeQuery(sql);
            rs.next();
            return Long.valueOf(rs.getLong(1));
        } finally {
            s.close();
        }
    }

    @Override
    public void setId(PreparedStatement ps, int index, Serializable value) throws SQLException {
        switch (idType) {
        case VARCHAR:
            ps.setObject(index, value);
            break;
        case UUID:
            ps.setObject(index, value, Types.OTHER);
            break;
        case SEQUENCE:
            setIdLong(ps, index, value);
            break;
        default:
            throw new AssertionError();
        }
    }

    @SuppressWarnings("boxing")
    public Serializable getId(ResultSet rs, int index) throws SQLException {
        switch (idType) {
        case VARCHAR:
        case UUID:
            return rs.getString(index);
        case SEQUENCE:
            return rs.getLong(index);
        default:
            throw new AssertionError();
        }
    }

    @Override
    public void setToPreparedStatement(PreparedStatement ps, int index, Serializable value, Column column)
            throws SQLException {
        switch (column.getJdbcType()) {
        case Types.VARCHAR:
        case Types.CLOB:
            setToPreparedStatementString(ps, index, value, column);
            return;
        case Types.BIT:
            ps.setBoolean(index, ((Boolean) value).booleanValue());
            return;
        case Types.SMALLINT:
            ps.setInt(index, ((Long) value).intValue());
            return;
        case Types.INTEGER:
        case Types.BIGINT:
            ps.setLong(index, ((Number) value).longValue());
            return;
        case Types.DOUBLE:
            ps.setDouble(index, ((Double) value).doubleValue());
            return;
        case Types.TIMESTAMP:
            ps.setTimestamp(index, getTimestampFromCalendar((Calendar) value));
            return;
        case Types.ARRAY:
            int jdbcBaseType = column.getJdbcBaseType();
            String jdbcBaseTypeName = column.getSqlBaseTypeString();
            if (jdbcBaseType == Types.TIMESTAMP) {
                value = getTimestampFromCalendar((Serializable[]) value);
            }
            Array array = ps.getConnection().createArrayOf(jdbcBaseTypeName, (Object[]) value);
            ps.setArray(index, array);
            return;
        case Types.OTHER:
            ColumnType type = column.getType();
            if (type.isId()) {
                setId(ps, index, value);
                return;
            } else if (type == ColumnType.FTSTORED) {
                ps.setString(index, (String) value);
                return;
            }
            throw new SQLException("Unhandled type: " + column.getType());
        default:
            throw new SQLException("Unhandled JDBC type: " + column.getJdbcType());
        }
    }

    @Override
    @SuppressWarnings("boxing")
    public Serializable getFromResultSet(ResultSet rs, int index, Column column) throws SQLException {
        int jdbcType = rs.getMetaData().getColumnType(index);
        if (column.getJdbcType() == Types.ARRAY && jdbcType != Types.ARRAY) {
            jdbcType = column.getJdbcBaseType();
        } else {
            jdbcType = column.getJdbcType();
        }
        switch (jdbcType) {
        case Types.VARCHAR:
        case Types.CLOB:
            return getFromResultSetString(rs, index, column);
        case Types.BIT:
            return rs.getBoolean(index);
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
            return rs.getLong(index);
        case Types.DOUBLE:
            return rs.getDouble(index);
        case Types.TIMESTAMP:
            return getCalendarFromTimestamp(rs.getTimestamp(index));
        case Types.ARRAY:
            Array array = rs.getArray(index);
            if (array == null) {
                return null;
            }
            if (array.getBaseType() == Types.TIMESTAMP) {
                return getCalendarFromTimestamp((Timestamp[]) array.getArray());
            } else {
                return (Serializable) array.getArray();
            }
        case Types.OTHER:
            ColumnType type = column.getType();
            if (type.isId()) {
                return getId(rs, index);
            }
            throw new SQLException("Unhandled type: " + column.getType());
        }
        throw new SQLException("Unhandled JDBC type: " + column.getJdbcType() + " for type "
                + column.getType().toString());
    }

    @Override
    protected int getMaxNameSize() {
        return 63;
    }

    @Override
    public String getColumnName(String name) {
        // ignore suffixed "_" when checking for reservedness
        String n = name.replaceAll("_+$", "");
        for (String reserved : RESERVED_COLUMN_NAMES) {
            if (n.equals(reserved)) {
                // reserved, add one more suffix "_"
                name += "_";
                break;
            }
        }
        return super.getColumnName(name);
    }

    @Override
    public String getCreateFulltextIndexSql(String indexName, String quotedIndexName, Table table,
            List<Column> columns, Model model) {
        String sql;
        if (compatibilityFulltextTable) {
            sql = "CREATE INDEX %s ON %s USING GIN(%s)";
        } else {
            sql = "CREATE INDEX %s ON %s USING GIN(NX_TO_TSVECTOR(%s))";
        }
        return String.format(sql, quotedIndexName.toLowerCase(), table.getQuotedName(), columns.get(0).getQuotedName());
    }

    // must not be interpreted as a regexp, we split on it
    protected static final String FT_LIKE_SEP = " @#AND#@ ";

    protected static final String FT_LIKE_COL = "??";

    /**
     * {@inheritDoc}
     * <p>
     * The result of this is passed to {@link #getFulltextScoredMatchInfo}.
     */
    @Override
    public String getDialectFulltextQuery(String query) {
        query = query.replace(" & ", " "); // PostgreSQL compatibility BBB
        query = PREFIX_PATTERN.matcher(query).replaceAll(PREFIX_REPL);
        FulltextQuery ft = FulltextQueryAnalyzer.analyzeFulltextQuery(query);
        if (ft == null) {
            return ""; // won't match anything
        }
        if (!FulltextQueryAnalyzer.hasPhrase(ft)) {
            return FulltextQueryAnalyzer.translateFulltext(ft, "|", "&", "& !", "");
        }
        if (compatibilityFulltextTable) {
            throw new QueryMakerException("Cannot use phrase search in fulltext compatibilty mode. "
                    + "Please upgrade the fulltext table: " + query);
        }
        /*
         * Phrase search. We have to do the phrase query using a LIKE, but for performance we pre-filter using as many
         * fulltext matches as possible by breaking some of the phrases into words. We do an AND of the two. 1.
         * pre-filter using fulltext on a query that is a superset of the original query,
         */
        FulltextQuery broken = breakPhrases(ft);
        String ftsql = FulltextQueryAnalyzer.translateFulltext(broken, "|", "&", "& !", "");
        /*
         * 2. AND with a LIKE-based search for all terms, except those that are already exactly matched by the first
         * part, i.e., toplevel ANDed non-phrases.
         */
        FulltextQuery noand = removeToplevelAndedWords(ft);
        if (noand != null) {
            StringBuilder buf = new StringBuilder();
            generateLikeSql(noand, buf);
            ftsql += FT_LIKE_SEP + buf.toString();

        }
        return ftsql;
    }

    /**
     * Returns a fulltext query that is a superset of the original one and does not have phrase searches.
     * <p>
     * Negative phrases (which are at AND level) are removed, positive phrases are split into ANDed words.
     */
    protected static FulltextQuery breakPhrases(FulltextQuery ft) {
        FulltextQuery newFt = new FulltextQuery();
        if (ft.op == Op.AND || ft.op == Op.OR) {
            List<FulltextQuery> newTerms = new LinkedList<FulltextQuery>();
            for (FulltextQuery term : ft.terms) {
                FulltextQuery broken = breakPhrases(term);
                if (broken == null) {
                    // remove negative phrase
                } else if (ft.op == Op.AND && broken.op == Op.AND) {
                    // associativity (sub-AND hoisting)
                    newTerms.addAll(broken.terms);
                } else {
                    newTerms.add(broken);
                }
            }
            if (newTerms.size() == 1) {
                // single-term parenthesis elimination
                newFt = newTerms.get(0);
            } else {
                newFt.op = ft.op;
                newFt.terms = newTerms;
            }
        } else {
            boolean isPhrase = ft.isPhrase();
            if (!isPhrase) {
                newFt = ft;
            } else if (ft.op == Op.WORD) {
                // positive phrase
                // split it
                List<FulltextQuery> newTerms = new LinkedList<FulltextQuery>();
                for (String subword : ft.word.split(" ")) {
                    FulltextQuery sft = new FulltextQuery();
                    sft.op = Op.WORD;
                    sft.word = subword;
                    newTerms.add(sft);
                }
                newFt.op = Op.AND;
                newFt.terms = newTerms;
            } else {
                // negative phrase
                // removed
                newFt = null;
            }
        }
        return newFt;
    }

    /**
     * Removes toplevel ANDed simple words from the query.
     */
    protected static FulltextQuery removeToplevelAndedWords(FulltextQuery ft) {
        if (ft.op == Op.OR || ft.op == Op.NOTWORD) {
            return ft;
        }
        if (ft.op == Op.WORD) {
            if (ft.isPhrase()) {
                return ft;
            }
            return null;
        }
        List<FulltextQuery> newTerms = new LinkedList<FulltextQuery>();
        for (FulltextQuery term : ft.terms) {
            if (term.op == Op.NOTWORD) {
                newTerms.add(term);
            } else { // Op.WORD
                if (term.isPhrase()) {
                    newTerms.add(term);
                }
            }
        }
        if (newTerms.isEmpty()) {
            return null;
        } else if (newTerms.size() == 1) {
            // single-term parenthesis elimination
            return newTerms.get(0);
        } else {
            FulltextQuery newFt = new FulltextQuery();
            newFt.op = Op.AND;
            newFt.terms = newTerms;
            return newFt;
        }
    }

    // turn non-toplevel ANDed single words into SQL
    // abc "foo bar" -"gee man"
    // -> ?? LIKE '% foo bar %' AND ?? NOT LIKE '% gee man %'
    // ?? is a pseudo-parameter for the col
    protected static void generateLikeSql(FulltextQuery ft, StringBuilder buf) {
        if (ft.op == Op.AND || ft.op == Op.OR) {
            buf.append('(');
            boolean first = true;
            for (FulltextQuery term : ft.terms) {
                if (!first) {
                    if (ft.op == Op.AND) {
                        buf.append(" AND ");
                    } else { // Op.OR
                        buf.append(" OR ");
                    }
                }
                first = false;
                generateLikeSql(term, buf);
            }
            buf.append(')');
        } else {
            buf.append(FT_LIKE_COL);
            if (ft.op == Op.NOTWORD) {
                buf.append(" NOT");
            }
            buf.append(" LIKE '% ");
            String word = ft.word.toLowerCase();
            // SQL escaping
            word = word.replace("'", "''");
            word = word.replace("\\", ""); // don't take chances
            word = word.replace(PREFIX_SEARCH, "%");
            buf.append(word);
            if (!word.endsWith("%")) {
                buf.append(" %");
            }
            buf.append("'");
        }
    }

    // OLD having problems in pre-9.2 (NXP-9228)
    // SELECT ...,
    // TS_RANK_CD(NX_TO_TSVECTOR(fulltext), nxquery, 32) as nxscore
    // FROM ...
    // LEFT JOIN fulltext ON fulltext.id = hierarchy.id,
    // TO_TSQUERY('french', ?) nxquery
    // WHERE ...
    // AND nxquery @@ NX_TO_TSVECTOR(fulltext)
    // AND fulltext LIKE '% foo bar %' -- when phrase search
    // ORDER BY nxscore DESC

    // NEW
    // SELECT ...,
    // TS_RANK_CD(NX_TO_TSVECTOR(fulltext), TO_TSQUERY('french', ?), 32) as
    // nxscore
    // FROM ...
    // LEFT JOIN fulltext ON fulltext.id = hierarchy.id
    // WHERE ...
    // AND TO_TSQUERY('french', ?) @@ NX_TO_TSVECTOR(fulltext)
    // AND fulltext LIKE '% foo bar %' -- when phrase search
    // ORDER BY nxscore DESC
    @Override
    public FulltextMatchInfo getFulltextScoredMatchInfo(String fulltextQuery, String indexName, int nthMatch,
            Column mainColumn, Model model, Database database) {
        String indexSuffix = model.getFulltextIndexSuffix(indexName);
        Table ft = database.getTable(model.FULLTEXT_TABLE_NAME);
        Column ftMain = ft.getColumn(model.MAIN_KEY);
        Column ftColumn = ft.getColumn(model.FULLTEXT_FULLTEXT_KEY + indexSuffix);
        String ftColumnName = ftColumn.getFullQuotedName();
        String nthSuffix = nthMatch == 1 ? "" : String.valueOf(nthMatch);
        FulltextMatchInfo info = new FulltextMatchInfo();
        info.joins = new ArrayList<Join>();
        if (nthMatch == 1) {
            // Need only one JOIN involving the fulltext table
            info.joins.add(new Join(Join.INNER, ft.getQuotedName(), null, null, ftMain.getFullQuotedName(),
                    mainColumn.getFullQuotedName()));
        }
        /*
         * for phrase search, fulltextQuery may contain a LIKE part
         */
        String like;
        if (fulltextQuery.contains(FT_LIKE_SEP)) {
            String[] tmp = fulltextQuery.split(FT_LIKE_SEP, 2);
            fulltextQuery = tmp[0];
            like = tmp[1].replace(FT_LIKE_COL, ftColumnName);
        } else {
            like = null;
        }
        String tsquery = String.format("TO_TSQUERY('%s', ?)", fulltextAnalyzer);
        String tsvector;
        if (compatibilityFulltextTable) {
            tsvector = ftColumnName;
        } else {
            tsvector = String.format("NX_TO_TSVECTOR(%s)", ftColumnName);
        }
        String where = String.format("(%s @@ %s)", tsquery, tsvector);
        if (like != null) {
            where += " AND (" + like + ")";
        }
        info.whereExpr = where;
        info.whereExprParam = fulltextQuery;
        info.scoreExpr = String.format("TS_RANK_CD(%s, %s, 32)", tsvector, tsquery);
        info.scoreExprParam = fulltextQuery;
        info.scoreAlias = "_nxscore" + nthSuffix;
        info.scoreCol = new Column(mainColumn.getTable(), null, ColumnType.DOUBLE, null);
        return info;
    }

    @Override
    public boolean getMaterializeFulltextSyntheticColumn() {
        return true;
    }

    @Override
    public int getFulltextIndexedColumns() {
        return 1;
    }

    @Override
    public String getFreeVariableSetterForType(ColumnType type) {
        if (type == ColumnType.FTSTORED && compatibilityFulltextTable) {
            return "NX_TO_TSVECTOR(?)";
        }
        return "?";
    }

    @Override
    public boolean supportsUpdateFrom() {
        return true;
    }

    @Override
    public boolean doesUpdateFromRepeatSelf() {
        return false;
    }

    @Override
    public boolean needsAliasForDerivedTable() {
        return true;
    }

    @Override
    public boolean supportsIlike() {
        return true;
    }

    @Override
    public boolean supportsReadAcl() {
        return aclOptimizationsEnabled;
    }

    @Override
    public String getPrepareUserReadAclsSql() {
        return "SELECT nx_prepare_user_read_acls(?)";
    }

    @Override
    public String getReadAclsCheckSql(String userIdCol) {
        return String.format("%s = md5(array_to_string(?, '%s'))", userIdCol, getUsersSeparator());
    }

    @Override
    public String getUpdateReadAclsSql() {
        return "SELECT nx_update_read_acls();";
    }

    @Override
    public String getRebuildReadAclsSql() {
        return "SELECT nx_rebuild_read_acls();";
    }

    @Override
    public String getSecurityCheckSql(String idColumnName) {
        return String.format("NX_ACCESS_ALLOWED(%s, ?, ?)", idColumnName);
    }

    @Override
    public boolean supportsAncestorsTable() {
        return true;
    }

    @Override
    public String getInTreeSql(String idColumnName) {
        if (pathOptimizationsEnabled) {
            // TODO SEQUENCE
            String cast = idType == DialectIdType.UUID ? "::uuid[]" : "";
            return String.format("EXISTS(SELECT 1 FROM ancestors WHERE id = %s AND ARRAY[?]%s <@ ancestors)",
                    idColumnName, cast);
        } else {
            return String.format("%s IN (SELECT * FROM nx_children(?))", idColumnName);
        }
    }

    @Override
    public String getMatchMixinType(Column mixinsColumn, String mixin, boolean positive, String[] returnParam) {
        returnParam[0] = mixin;
        String sql = "ARRAY[?]::varchar[] <@ " + mixinsColumn.getFullQuotedName();
        return positive ? sql : "NOT(" + sql + ")";
    }

    @Override
    public boolean supportsSysNameArray() {
        return true;
    }

    @Override
    public boolean supportsArrays() {
        return true;
    }

    @Override
    public boolean supportsArrayColumns() {
        return true;
    }

    public static class ArraySubQueryPostgreSQL extends ArraySubQuery {

        protected Dialect dialect = null;

        protected Table fakeSubqueryTableAlias = null;

        public ArraySubQueryPostgreSQL(Column arrayColumn, String alias) {
            super(arrayColumn, alias);
            dialect = arrayColumn.getTable().getDialect();
            fakeSubqueryTableAlias = new TableAlias(arrayColumn.getTable(), alias);
        }

        @Override
        public Column getSubQueryIdColumn() {
            Column column = fakeSubqueryTableAlias.getColumn(Model.MAIN_KEY);
            return new ArraySubQueryPostgreSQLColumn(column.getPhysicalName(), column.getType());
        }

        @Override
        public Column getSubQueryValueColumn() {
            return new ArraySubQueryPostgreSQLColumn(Model.COLL_TABLE_VALUE_KEY, arrayColumn.getBaseType());
        }

        public class ArraySubQueryPostgreSQLColumn extends Column {
            private static final long serialVersionUID = 1L;

            ArraySubQueryPostgreSQLColumn(String columnName, ColumnType columnType) {
                super(fakeSubqueryTableAlias, columnName, columnType, columnName);
            }

            @Override
            public String getFullQuotedName() {
                return dialect.openQuote() + subQueryAlias + dialect.closeQuote() + '.' + getQuotedName();
            }
        }

        @Override
        public String toSql() {
            Table table = arrayColumn.getTable();
            return String.format("(SELECT %s, UNNEST(%s) AS %s, generate_subscripts(%s, 1) AS %s FROM %s) ",
                    table.getColumn(Model.MAIN_KEY).getQuotedName(), arrayColumn.getQuotedName(),
                    Model.COLL_TABLE_VALUE_KEY, arrayColumn.getQuotedName(), Model.COLL_TABLE_POS_KEY,
                    table.getRealTable().getQuotedName());
        }
    }

    @Override
    public ArraySubQuery getArraySubQuery(Column arrayColumn, String subQueryAlias) throws QueryMakerException {
        return new ArraySubQueryPostgreSQL(arrayColumn, subQueryAlias);
    }

    @Override
    public String getArrayElementString(String arrayColumnName, int arrayElementIndex) throws QueryMakerException {
        // PostgreSQL arrays index start at 1
        return arrayColumnName + "[" + (arrayElementIndex + 1) + "]";
    }

    @Override
    public String getArrayInSql(Column arrayColumn, String cast, boolean positive, List<Serializable> params) {
        StringBuilder sql = new StringBuilder();
        if (!positive) {
            sql.append("(NOT(");
        }
        if (params.size() == 1) {
            // ? = ANY(arrayColumn)
            sql.append("? = ANY(");
            sql.append(arrayColumn.getFullQuotedName());
            if (cast != null) {
                // DATE cast
                sql.append("::");
                sql.append(cast);
                sql.append("[]");
            }
            sql.append(")");
        } else {
            // arrayColumn && ARRAY[?, ?, ?]
            sql.append(arrayColumn.getFullQuotedName());
            sql.append(" && ");
            sql.append("ARRAY[");
            for (int i = 0; i < params.size(); i++) {
                if (i != 0) {
                    sql.append(", ");
                }
                sql.append('?');
            }
            sql.append("]::");
            sql.append(arrayColumn.getSqlTypeString());
        }
        if (!positive) {
            sql.append(") OR ");
            sql.append(arrayColumn.getFullQuotedName());
            sql.append(" IS NULL)");
        }
        return sql.toString();
    }

    @Override
    public String getArrayLikeSql(Column arrayColumn, String refName, boolean positive, Table dataHierTable) {
        return getArrayOpSql(arrayColumn, refName, positive, dataHierTable, "LIKE");
    }

    @Override
    public String getArrayIlikeSql(Column arrayColumn, String refName, boolean positive, Table dataHierTable) {
        return getArrayOpSql(arrayColumn, refName, positive, dataHierTable, "ILIKE");
    }

    protected String getArrayOpSql(Column arrayColumn, String refName, boolean positive, Table dataHierTable, String op) {
        Table table = arrayColumn.getTable();
        String tableAliasName = openQuote() + getTableName(refName) + closeQuote();
        String sql = String.format("EXISTS (SELECT 1 FROM %s AS %s WHERE %s = %s AND %s %s ?)",
                getArraySubQuery(arrayColumn, tableAliasName).toSql(), tableAliasName,
                dataHierTable.getColumn(Model.MAIN_KEY).getFullQuotedName(),
                tableAliasName + '.' + table.getColumn(Model.MAIN_KEY).getQuotedName(), tableAliasName + '.'
                        + Model.COLL_TABLE_VALUE_KEY, op);
        if (!positive) {
            sql = "NOT(" + sql + ")";
        }
        return sql;
    }

    @Override
    public Array createArrayOf(int type, Object[] elements, Connection connection) throws SQLException {
        if (elements == null || elements.length == 0) {
            return null;
        }
        String typeName;
        switch (type) {
        case Types.VARCHAR:
            typeName = "varchar";
            break;
        case Types.CLOB:
            typeName = "text";
            break;
        case Types.BIT:
            typeName = "bool";
            break;
        case Types.BIGINT:
            typeName = "int8";
            break;
        case Types.DOUBLE:
            typeName = "float8";
            break;
        case Types.TIMESTAMP:
            typeName = "timestamp";
            break;
        case Types.SMALLINT:
            typeName = "int2";
            break;
        case Types.INTEGER:
            typeName = "int4";
            break;
        case Types.OTHER: // id
            switch (idType) {
            case VARCHAR:
                typeName = "varchar";
                break;
            case UUID:
                typeName = "uuid";
                break;
            case SEQUENCE:
                typeName = "int8";
                break;
            default:
                throw new AssertionError("Unknown id type: " + idType);
            }
            break;
        default:
            throw new AssertionError("Unknown type: " + type);
        }
        return connection.createArrayOf(typeName, elements);
    }

    @Override
    public String getSQLStatementsFilename() {
        return "nuxeovcs/postgresql.sql.txt";
    }

    @Override
    public String getTestSQLStatementsFilename() {
        return "nuxeovcs/postgresql.test.sql.txt";
    }

    @Override
    public Map<String, Serializable> getSQLStatementsProperties(Model model, Database database) {
        Map<String, Serializable> properties = new HashMap<String, Serializable>();
        switch (idType) {
        case VARCHAR:
            properties.put("idType", "varchar(36)");
            properties.put("idTypeParam", "varchar");
            properties.put("idNotPresent", "'-'");
            properties.put("sequenceEnabled", Boolean.FALSE);
            break;
        case UUID:
            properties.put("idType", "uuid");
            properties.put("idTypeParam", "uuid");
            properties.put("idNotPresent", "'00000000-FFFF-FFFF-FFFF-FFFF00000000'");
            properties.put("sequenceEnabled", Boolean.FALSE);
            break;
        case SEQUENCE:
            properties.put("idType", "int8");
            properties.put("idTypeParam", "int8");
            properties.put("idNotPresent", "-1");
            properties.put("sequenceEnabled", Boolean.TRUE);
            properties.put("idSequenceName", idSequenceName);
        }
        properties.put("aclOptimizationsEnabled", Boolean.valueOf(aclOptimizationsEnabled));
        properties.put("pathOptimizationsEnabled", Boolean.valueOf(pathOptimizationsEnabled));
        properties.put("fulltextAnalyzer", fulltextAnalyzer);
        properties.put("fulltextEnabled", Boolean.valueOf(!fulltextDisabled));
        properties.put("clusteringEnabled", Boolean.valueOf(clusteringEnabled));
        properties.put("proxiesEnabled", Boolean.valueOf(proxiesEnabled));
        properties.put("softDeleteEnabled", Boolean.valueOf(softDeleteEnabled));
        if (!fulltextDisabled) {
            Table ft = database.getTable(model.FULLTEXT_TABLE_NAME);
            properties.put("fulltextTable", ft.getQuotedName());
            FulltextConfiguration fti = model.getFulltextConfiguration();
            List<String> lines = new ArrayList<String>(fti.indexNames.size());
            for (String indexName : fti.indexNames) {
                String suffix = model.getFulltextIndexSuffix(indexName);
                Column ftft = ft.getColumn(model.FULLTEXT_FULLTEXT_KEY + suffix);
                Column ftst = ft.getColumn(model.FULLTEXT_SIMPLETEXT_KEY + suffix);
                Column ftbt = ft.getColumn(model.FULLTEXT_BINARYTEXT_KEY + suffix);
                String concat;
                if (compatibilityFulltextTable) {
                    // tsvector
                    concat = "  NEW.%s := COALESCE(NEW.%s, ''::TSVECTOR) || COALESCE(NEW.%s, ''::TSVECTOR);";
                } else {
                    // text with space at beginning and end
                    concat = "  NEW.%s := ' ' || COALESCE(NEW.%s, '') || ' ' || COALESCE(NEW.%s, '') || ' ';";
                }
                String line = String.format(concat, ftft.getQuotedName(), ftst.getQuotedName(), ftbt.getQuotedName());
                lines.add(line);
            }
            properties.put("fulltextTriggerStatements", StringUtils.join(lines, "\n"));
        }
        String[] permissions = NXCore.getSecurityService().getPermissionsToCheck(SecurityConstants.BROWSE);
        List<String> permsList = new LinkedList<String>();
        for (String perm : permissions) {
            permsList.add("('" + perm + "')");
        }
        properties.put("readPermissions", StringUtils.join(permsList, ", "));
        properties.put("usersSeparator", getUsersSeparator());
        properties.put("everyone", SecurityConstants.EVERYONE);
        properties.put("readAclMaxSize", Integer.toString(readAclMaxSize));
        properties.put("unlogged", unloggedKeyword);
        return properties;
    }

    @Override
    public boolean preCreateTable(Connection connection, Table table, Model model, Database database)
            throws SQLException {
        String tableKey = table.getKey();
        if (model.HIER_TABLE_NAME.equals(tableKey)) {
            hierarchyCreated = true;
            return true;
        }
        if (model.ANCESTORS_TABLE_NAME.equals(tableKey)) {
            if (hierarchyCreated) {
                // database initialization
                return true;
            }
            // upgrade of an existing database
            // check hierarchy size
            String sql = "SELECT COUNT(*) FROM hierarchy WHERE NOT isproperty";
            Statement s = connection.createStatement();
            ResultSet rs = s.executeQuery(sql);
            rs.next();
            long count = rs.getLong(1);
            rs.close();
            s.close();
            if (count > 100000) {
                // if the hierarchy table is too big, tell the admin to do the
                // init by hand
                pathOptimizationsEnabled = false;
                log.error("Table ANCESTORS not initialized automatically because table HIERARCHY is too big. "
                        + "Upgrade by hand by calling: SELECT nx_init_ancestors()");
            }
            return true;
        }
        return true;
    }

    @Override
    public List<String> getPostCreateTableSqls(Table table, Model model, Database database) {
        if (Model.ANCESTORS_TABLE_NAME.equals(table.getKey())) {
            List<String> sqls = new ArrayList<String>();
            if (pathOptimizationsEnabled) {
                sqls.add("SELECT nx_init_ancestors()");
            } else {
                log.info("Path optimizations disabled");
            }
            return sqls;
        }
        return Collections.emptyList();
    }

    @Override
    public void existingTableDetected(Connection connection, Table table, Model model, Database database)
            throws SQLException {
        if (Model.ANCESTORS_TABLE_NAME.equals(table.getKey())) {
            if (!pathOptimizationsEnabled) {
                log.info("Path optimizations disabled");
                return;
            }
            // check if we want to initialize the descendants table now, or log
            // a warning if the hierarchy table is too big
            String sql = "SELECT id FROM ancestors LIMIT 1";
            Statement s = connection.createStatement();
            ResultSet rs = s.executeQuery(sql);
            boolean empty = !rs.next();
            rs.close();
            s.close();
            if (empty) {
                pathOptimizationsEnabled = false;
                log.error("Table ANCESTORS empty, must be upgraded by hand by calling: " + "SELECT nx_init_ancestors()");
                log.info("Path optimizations disabled");
            }
        }
    }

    @Override
    public boolean isClusteringSupported() {
        return true;
    }

    @Override
    public String getClusterInsertInvalidations() {
        return "SELECT NX_CLUSTER_INVAL(?, ?, ?, ?)";
    }

    @Override
    public String getClusterGetInvalidations() {
        return "DELETE FROM cluster_invals WHERE nodeid = ? RETURNING id, fragments, kind";
    }

    @Override
    public boolean isConnectionClosedException(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        // org.postgresql.util.PSQLException. message: An I/O error occured
        // while sending to the backend
        // Caused by: java.net.SocketException. message: Broken pipe
        if (t instanceof SocketException) {
            return true;
        }
        // org.postgresql.util.PSQLException. message: FATAL: terminating
        // connection due to administrator command
        String message = t.getMessage();
        if (message != null && message.contains("FATAL:")) {
            return true;
        }
        if (t instanceof SQLException) {
            // org.postgresql.util.PSQLException: This connection has been
            // closed.
            if ("08003".equals(((SQLException) t).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isConcurrentUpdateException(Throwable t) {
        while (t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof SQLException) {
            String sqlState = ((SQLException) t).getSQLState();
            if ("23503".equals(sqlState)) {
                // insert or update on table ... violates foreign key constraint
                return true;
            }
            if ("23505".equals(sqlState)) {
                // duplicate key value violates unique constraint
                return true;
            }
            if ("40P01".equals(sqlState)) {
                // deadlock detected
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean supportsPaging() {
        return true;
    }

    @Override
    public String addPagingClause(String sql, long limit, long offset) {
        return sql + String.format(" LIMIT %d OFFSET %d", limit, offset);
    }

    @Override
    public boolean supportsWith() {
        return false; // don't activate until proven useful
        // return supportsWith;
    }

    @Override
    public void performAdditionalStatements(Connection connection) throws SQLException {
        // Warn user if BROWSE permissions has changed
        Set<String> dbPermissions = new HashSet<String>();
        String sql = "SELECT * FROM aclr_permission";
        Statement s = connection.createStatement();
        ResultSet rs = s.executeQuery(sql);
        while (rs.next()) {
            dbPermissions.add(rs.getString(1));
        }
        rs.close();
        s.close();
        Set<String> confPermissions = new HashSet<String>();
        SecurityService securityService = NXCore.getSecurityService();
        for (String perm : securityService.getPermissionsToCheck(SecurityConstants.BROWSE)) {
            confPermissions.add(perm);
        }
        if (!dbPermissions.equals(confPermissions)) {
            log.error("Security permission for BROWSE has changed, you need to rebuild the optimized read acls:"
                    + "DROP TABLE aclr_permission; DROP TABLE aclr; then restart.");
        }
    }

    public String getUsersSeparator() {
        if (usersSeparator == null) {
            return DEFAULT_USERS_SEPARATOR;
        }
        return usersSeparator;
    }

    @Override
    public String getValidationQuery() {
        return "";
    }

    @Override
    public String getAncestorsIdsSql() {
        return "SELECT NX_ANCESTORS(?)";
    }

    @Override
    public boolean needsNullsLastOnDescSort() {
        return true;
    }

    @Override
    public String getDateCast() {
        // this is more amenable to being indexed than a CAST
        return "DATE(%s)";
    }

    @Override
    public String castIdToVarchar(String expr) {
        switch (idType) {
        case VARCHAR:
            return expr;
        case UUID:
            return expr + "::varchar";
        case SEQUENCE:
            return expr + "::varchar";
        default:
            throw new AssertionError("Unknown id type: " + idType);
        }
    }

    @Override
    public DialectIdType getIdType() {
        return idType;
    }

    @Override
    public String getSoftDeleteSql() {
        return "SELECT NX_DELETE(?, ?)";
    }

    @Override
    public String getSoftDeleteCleanupSql() {
        return "SELECT NX_DELETE_PURGE(?, ?)";
    }

    @Override
    public String getBinaryFulltextSql(List<String> columns) {
        if (compatibilityFulltextTable) {
            // extract tokens from tsvector
            List<String> columnsAs = new ArrayList<String>(columns.size());
            for (String col : columns) {
                columnsAs.add("regexp_replace(" + col + "::text, $$'|'\\:[^']*'?$$, ' ', 'g')");
            }
            return "SELECT " + StringUtils.join(columnsAs, ", ") + " FROM fulltext WHERE id=?";
        }
        return super.getBinaryFulltextSql(columns);
    }

}
