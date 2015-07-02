/*
 * (C) Copyright 2014 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl-2.1.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     bdelbosc
 */
package org.nuxeo.elasticsearch.query;

import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.AndFilterBuilder;
import org.elasticsearch.index.query.FilterBuilder;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.AbstractAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.filter.FilterAggregationBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.core.api.SortInfo;
import org.nuxeo.ecm.core.schema.types.Type;
import org.nuxeo.ecm.core.security.SecurityService;
import org.nuxeo.ecm.platform.query.api.Aggregate;
import org.nuxeo.ecm.platform.query.api.Bucket;
import org.nuxeo.elasticsearch.ElasticSearchConstants;
import org.nuxeo.elasticsearch.aggregate.AggregateEsBase;
import org.nuxeo.elasticsearch.fetcher.EsFetcher;
import org.nuxeo.elasticsearch.fetcher.Fetcher;
import org.nuxeo.elasticsearch.fetcher.VcsFetcher;
import org.nuxeo.runtime.api.Framework;

import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.nuxeo.ecm.core.api.security.SecurityConstants.UNSUPPORTED_ACL;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.ACL_FIELD;
import static org.nuxeo.elasticsearch.ElasticSearchConstants.FETCH_DOC_FROM_ES_PROPERTY;

/**
 * Elasticsearch query buidler for the Nuxeo ES api.
 *
 * @since 5.9.5
 */
public class NxQueryBuilder {

    private static final int DEFAULT_LIMIT = 10;

    private int limit = DEFAULT_LIMIT;

    private static final String AGG_FILTER_SUFFIX = "_filter";

    private final CoreSession session;

    private final List<SortInfo> sortInfos = new ArrayList<>();

    private final List<String> repositories = new ArrayList<>();

    private final List<AggregateEsBase<? extends Bucket>> aggregates = new ArrayList<>();

    private int offset = 0;

    private String nxql;

    private org.elasticsearch.index.query.QueryBuilder esQueryBuilder;

    private boolean fetchFromElasticsearch = false;

    private boolean searchOnAllRepo = false;

    private String[] selectFields = {ElasticSearchConstants.ID_FIELD};

    private Map<String, Type> selectFieldsAndTypes;

    private boolean returnsDocuments = true;

    public NxQueryBuilder(CoreSession coreSession) {
        session = coreSession;
        repositories.add(coreSession.getRepositoryName());
        fetchFromElasticsearch = Boolean.parseBoolean(Framework.getProperty(FETCH_DOC_FROM_ES_PROPERTY, "false"));
    }

    public static String getAggregateFilterId(Aggregate agg) {
        return agg.getId() + AGG_FILTER_SUFFIX;
    }

    /**
     * No more than that many documents will be returned. Default to {DEFAULT_LIMIT}, Use -1 to return all documents.
     */
    public NxQueryBuilder limit(int limit) {
        if (limit < 0) {
            limit = Integer.MAX_VALUE;
        }
        this.limit = limit;
        return this;
    }

    /**
     * Says to skip that many documents before beginning to return documents. If both offset and limit appear, then
     * offset documents are skipped before starting to count the limit documents that are returned.
     */
    public NxQueryBuilder offset(int offset) {
        this.offset = offset;
        return this;
    }

    public NxQueryBuilder addSort(SortInfo sortInfo) {
        sortInfos.add(sortInfo);
        return this;
    }

    public NxQueryBuilder addSort(SortInfo[] sortInfos) {
        if (sortInfos != null && sortInfos.length > 0) {
            Collections.addAll(this.sortInfos, sortInfos);
        }
        return this;
    }

    /**
     * Build the query from a NXQL string. You should either use nxql, either esQuery, not both.
     */
    public NxQueryBuilder nxql(String nxql) {
        this.nxql = nxql;
        this.esQueryBuilder = null;
        return this;
    }

    /**
     * Build the query using the Elasticsearch QueryBuilder API. You should either use nxql, either esQuery, not both.
     */
    public NxQueryBuilder esQuery(QueryBuilder queryBuilder) {
        this.esQueryBuilder = queryBuilder;
        this.nxql = null;
        return this;
    }

    /**
     * Fetch the documents from the Elasticsearch _source field.
     */
    public NxQueryBuilder fetchFromElasticsearch() {
        this.fetchFromElasticsearch = true;
        return this;
    }

    /**
     * Fetch the documents using VCS (database) engine. This is done by default
     */
    public NxQueryBuilder fetchFromDatabase() {
        this.fetchFromElasticsearch = false;
        return this;
    }

    public NxQueryBuilder addAggregate(AggregateEsBase<? extends Bucket> aggregate) {
        aggregates.add(aggregate);
        return this;
    }

    public NxQueryBuilder addAggregates(List<AggregateEsBase<? extends Bucket>> aggregates) {
        if (aggregates != null && !aggregates.isEmpty()) {
            this.aggregates.addAll(aggregates);
        }
        return this;
    }

    public int getLimit() {
        return limit;
    }

    public int getOffset() {
        return offset;
    }

    public List<SortInfo> getSortInfos() {
        return sortInfos;
    }

    public String getNxql() {
        return nxql;
    }

    public boolean isFetchFromElasticsearch() {
        return fetchFromElasticsearch;
    }

    public CoreSession getSession() {
        return session;
    }

    /**
     * Get the Elasticsearch queryBuilder. Note that it returns only the query part without order, limits nor
     * aggregates, use the udpateRequest to get the full request.
     */
    public QueryBuilder makeQuery() {
        if (esQueryBuilder == null) {
            if (nxql != null) {
                esQueryBuilder = NxqlQueryConverter.toESQueryBuilder(nxql, session);
                // handle the built-in order by clause
                if (nxql.toLowerCase().contains("order by")) {
                    List<SortInfo> builtInSortInfos = NxqlQueryConverter.getSortInfo(nxql);
                    sortInfos.addAll(builtInSortInfos);
                }
                if (nxqlHasSelectClause(nxql)) {
                    selectFieldsAndTypes = NxqlQueryConverter.getSelectClauseFields(nxql);
                    selectFields = selectFieldsAndTypes.keySet().toArray(new String[0]);
                    returnsDocuments = false;
                }
                esQueryBuilder = addSecurityFilter(esQueryBuilder);
            }
        }
        return esQueryBuilder;
    }

    protected boolean nxqlHasSelectClause(String nxql) {
        String lowerNxql = nxql.toLowerCase();
        if (lowerNxql.startsWith("select") && !lowerNxql.startsWith("select * from")) {
            return true;
        }
        return false;
    }

    public SortBuilder[] getSortBuilders() {
        SortBuilder[] ret;
        if (sortInfos.isEmpty()) {
            return new SortBuilder[0];
        }
        ret = new SortBuilder[sortInfos.size()];
        int i = 0;
        for (SortInfo sortInfo : sortInfos) {
            ret[i++] = new FieldSortBuilder(sortInfo.getSortColumn()).order(sortInfo.getSortAscending() ? SortOrder.ASC
                    : SortOrder.DESC);
        }
        return ret;
    }

    protected FilterBuilder getAggregateFilter() {
        boolean hasFilter = false;
        AndFilterBuilder ret = FilterBuilders.andFilter();
        for (AggregateEsBase agg : aggregates) {
            FilterBuilder filter = agg.getEsFilter();
            if (filter != null) {
                ret.add(filter);
                hasFilter = true;
            }
        }
        if (!hasFilter) {
            return null;
        }
        return ret;
    }

    protected FilterBuilder getAggregateFilterExceptFor(String id) {
        boolean hasFilter = false;
        AndFilterBuilder ret = FilterBuilders.andFilter();
        for (AggregateEsBase agg : aggregates) {
            if (!agg.getId().equals(id)) {
                FilterBuilder filter = agg.getEsFilter();
                if (filter != null) {
                    ret.add(filter);
                    hasFilter = true;
                }
            }
        }
        if (!hasFilter) {
            return FilterBuilders.matchAllFilter();
        }
        return ret;
    }

    public List<AggregateEsBase<? extends Bucket>> getAggregates() {
        return aggregates;
    }

    public List<FilterAggregationBuilder> getEsAggregates() {
        List<FilterAggregationBuilder> ret = new ArrayList<>(aggregates.size());
        for (AggregateEsBase agg : aggregates) {
            FilterAggregationBuilder fagg = new FilterAggregationBuilder(getAggregateFilterId(agg));
            fagg.filter(getAggregateFilterExceptFor(agg.getId()));
            fagg.subAggregation(agg.getEsAggregate());
            ret.add(fagg);
        }
        return ret;
    }

    public void updateRequest(SearchRequestBuilder request) {
        // Set limits
        request.setFrom(getOffset()).setSize(getLimit());
        // Build query with security checks
        request.setQuery(makeQuery());
        // Add sort
        for (SortBuilder sortBuilder : getSortBuilders()) {
            request.addSort(sortBuilder);
        }
        // Add Aggregate
        for (AbstractAggregationBuilder aggregate : getEsAggregates()) {
            request.addAggregation(aggregate);
        }
        // Add Aggregate post filter
        FilterBuilder aggFilter = getAggregateFilter();
        if (aggFilter != null) {
            request.setPostFilter(aggFilter);
        }
        // Fields selection
        if (!isFetchFromElasticsearch()) {
            request.addFields(getSelectFields());
        }

    }

    protected QueryBuilder addSecurityFilter(QueryBuilder query) {
        AndFilterBuilder aclFilter;
        Principal principal = session.getPrincipal();
        if (principal == null
                || (principal instanceof NuxeoPrincipal && ((NuxeoPrincipal) principal).isAdministrator())) {
            return query;
        }
        String[] principals = SecurityService.getPrincipalsToCheck(principal);
        // we want an ACL that match principals but we discard
        // unsupported ACE that contains negative ACE
        aclFilter = FilterBuilders.andFilter(FilterBuilders.inFilter(ACL_FIELD, principals),
                FilterBuilders.notFilter(FilterBuilders.inFilter(ACL_FIELD, UNSUPPORTED_ACL)));
        return QueryBuilders.filteredQuery(query, aclFilter);
    }


    /**
     * Add a specific repository to search. Default search is done on the session repository only.
     *
     * @since 6.0
     */
    public NxQueryBuilder addSearchRepository(String repositoryName) {
        repositories.add(repositoryName);
        return this;
    }

    /**
     * Search on all available repositories.
     *
     * @since 6.0
     */
    public NxQueryBuilder searchOnAllRepositories() {
        searchOnAllRepo = true;
        return this;
    }

    /**
     * Return the list of repositories to search, or an empty list to search on all available repositories;
     *
     * @since 6.0
     */
    public List<String> getSearchRepositories() {
        if (searchOnAllRepo) {
            return Collections.<String>emptyList();
        }
        return repositories;
    }

    /**
     * @since 6.0
     */
    public Fetcher getFetcher(SearchResponse response, Map<String, String> repoNames) {
        if (isFetchFromElasticsearch()) {
            return new EsFetcher(session, response, repoNames);
        }
        return new VcsFetcher(session, response, repoNames);
    }

    /**
     * @since 7.2
     */
    public String[] getSelectFields() {
        return selectFields;
    }

    /**
     * @since 7.2
     */
    public Map<String, Type> getSelectFieldsAndTypes() {
        return selectFieldsAndTypes;
    }

    /**
     * @since 7.2
     */
    public boolean returnsDocuments() {
        return returnsDocuments;
    }
}
