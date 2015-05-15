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
 *     Nuxeo
 */

package org.nuxeo.elasticsearch.test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.commons.lang.SystemUtils;
import org.elasticsearch.index.query.QueryBuilder;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.impl.DocumentModelImpl;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.platform.query.api.PageProvider;
import org.nuxeo.ecm.platform.query.api.PageProviderDefinition;
import org.nuxeo.ecm.platform.query.api.PageProviderService;
import org.nuxeo.ecm.platform.query.api.WhereClauseDefinition;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.api.ElasticSearchIndexing;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.provider.ElasticSearchNativePageProvider;
import org.nuxeo.elasticsearch.provider.ElasticSearchNxqlPageProvider;
import org.nuxeo.elasticsearch.query.PageProviderQueryBuilder;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.transaction.TransactionHelper;

@SuppressWarnings("unchecked")
@RunWith(FeaturesRunner.class)
@Features({ RepositoryElasticSearchFeature.class })
@LocalDeploy({ "org.nuxeo.elasticsearch.core:pageprovider-test-contrib.xml",
        "org.nuxeo.elasticsearch.core:schemas-test-contrib.xml",
        "org.nuxeo.elasticsearch.core:elasticsearch-test-contrib.xml" })
public class TestPageProvider {

    @Inject
    protected CoreSession session;

    @Inject
    ElasticSearchIndexing esi;

    @Inject
    ElasticSearchAdmin esa;

    @Inject
    protected WorkManager workManager;

    @Inject
    ElasticSearchService ess;

    private int commandProcessed;

    // Number of processed command since the startTransaction
    public void assertNumberOfCommandProcessed(int processed) throws Exception {
        Assert.assertEquals(processed, esa.getTotalCommandProcessed() - commandProcessed);
    }

    /**
     * Wait for async worker completion then wait for indexing completion
     */
    public void waitForCompletion() throws Exception {
        workManager.awaitCompletion(20, TimeUnit.SECONDS);
        esa.prepareWaitForIndexing().get(20, TimeUnit.SECONDS);
        esa.refresh();
    }

    protected void startTransaction() {
        if (!TransactionHelper.isTransactionActive()) {
            TransactionHelper.startTransaction();
        }
        Assert.assertEquals(0, esa.getPendingWorkerCount());
        Assert.assertEquals(0, esa.getPendingCommandCount());
        commandProcessed = esa.getTotalCommandProcessed();
    }

    @Test
    public void ICanUseANativePageProvider() throws Exception {
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);

        PageProviderDefinition ppdef = pps.getPageProviderDefinition("NATIVE_PP_PATTERN");
        Assert.assertNotNull(ppdef);

        HashMap<String, Serializable> props = new HashMap<>();
        props.put(ElasticSearchNativePageProvider.CORE_SESSION_PROPERTY, (Serializable) session);
        long pageSize = 5;
        PageProvider<?> pp = pps.getPageProvider("NATIVE_PP_PATTERN", ppdef, null, null, pageSize, (long) 0, props);
        Assert.assertNotNull(pp);

        // create 10 docs
        startTransaction();
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc = session.createDocument(doc);
        }
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(10);

        startTransaction();
        // get current page
        List<DocumentModel> p = (List<DocumentModel>) pp.getCurrentPage();
        Assert.assertEquals(10, pp.getResultsCount());
        Assert.assertNotNull(p);
        Assert.assertEquals(pageSize, p.size());
        Assert.assertEquals(2, pp.getNumberOfPages());
        DocumentModel doc = p.get(0);
        Assert.assertEquals("TestMe9", doc.getTitle());

        pp.nextPage();
        p = (List<DocumentModel>) pp.getCurrentPage();
        Assert.assertEquals(pageSize, p.size());
        doc = p.get((int) pageSize - 1);
        Assert.assertEquals("TestMe0", doc.getTitle());
    }

    @Test
    public void ICanUseANxqlPageProvider() throws Exception {
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);

        PageProviderDefinition ppdef = pps.getPageProviderDefinition("NXQL_PP_PATTERN");
        Assert.assertNotNull(ppdef);

        HashMap<String, Serializable> props = new HashMap<String, Serializable>();
        props.put(ElasticSearchNativePageProvider.CORE_SESSION_PROPERTY, (Serializable) session);
        long pageSize = 5;
        ElasticSearchNxqlPageProvider pp = (ElasticSearchNxqlPageProvider) pps.getPageProvider("NXQL_PP_PATTERN",
                ppdef, null, null, pageSize, (long) 0, props);
        Assert.assertNotNull(pp);

        // create 10 docs
        startTransaction();
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc = session.createDocument(doc);
        }
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(10);

        startTransaction();
        // get current page
        List<DocumentModel> p = (List<DocumentModel>) pp.getCurrentPage();
        Assert.assertEquals(10, pp.getResultsCount());
        Assert.assertNotNull(p);
        Assert.assertEquals(pageSize, p.size());
        Assert.assertEquals(2, pp.getNumberOfPages());
        DocumentModel doc = p.get(0);
        Assert.assertEquals("TestMe9", doc.getTitle());

        pp.nextPage();
        p = (List<DocumentModel>) pp.getCurrentPage();
        Assert.assertEquals(pageSize, p.size());
        doc = p.get((int) pageSize - 1);
        Assert.assertEquals("TestMe0", doc.getTitle());

        pageSize = 0;
        ppdef = pps.getPageProviderDefinition("NXQL_PP_PATTERN2");
        Assert.assertNotNull(ppdef);
        pp = (ElasticSearchNxqlPageProvider) pps.getPageProvider("NXQL_PP_PATTERN2",
                ppdef, null, null, pageSize, (long) 0, props);
        Assert.assertNotNull(pp);
        p = (List<DocumentModel>) pp.getCurrentPage();
        Assert.assertEquals(10, pp.getResultsCount());
        Assert.assertEquals(10, p.size());
        doc = p.get(0);
        Assert.assertEquals("TestMe9", doc.getTitle());

    }

    @Test
    public void ICanUseANxqlPageProviderWithParameters() throws Exception {
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);

        PageProviderDefinition ppdef = pps.getPageProviderDefinition("nxql_search");
        Assert.assertNotNull(ppdef);
        HashMap<String, Serializable> props = new HashMap<String, Serializable>();
        props.put(ElasticSearchNativePageProvider.CORE_SESSION_PROPERTY, (Serializable) session);
        long pageSize = 5;
        PageProvider<?> pp = pps.getPageProvider("nxql_search", ppdef, null, null, pageSize, (long) 0, props);
        startTransaction();
        // create 10 docs
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc = session.createDocument(doc);
        }
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(10);
        startTransaction();

        // get current page
        String[] params = { "Select * from File where dc:title LIKE 'Test%'" };
        pp.setParameters(params);
        List<DocumentModel> p = (List<DocumentModel>) pp.getCurrentPage();
        String esquery = ((ElasticSearchNxqlPageProvider) pp).getCurrentQueryAsEsBuilder().toString();
        assertEqualsEvenUnderWindows("{\n" + "  \"filtered\" : {\n" + "    \"query\" : {\n" + "      \"match\" : {\n"
                + "        \"dc:title\" : {\n" + "          \"query\" : \"Test\",\n"
                + "          \"type\" : \"phrase_prefix\"\n" + "        }\n" + "      }\n" + "    },\n"
                + "    \"filter\" : {\n" + "      \"terms\" : {\n" + "        \"ecm:primaryType\" : [ \"File\" ]\n"
                + "      }\n" + "    }\n" + "  }\n" + "}", esquery);

        Assert.assertEquals(10, pp.getResultsCount());
        Assert.assertNotNull(p);
        Assert.assertEquals(pageSize, p.size());
        Assert.assertEquals(2, pp.getNumberOfPages());
        DocumentModel doc = p.get(0);
    }

    @Test
    public void ICanUseANxqlPageProviderWithFixedPart() throws Exception {
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);

        PageProviderDefinition ppdef = pps.getPageProviderDefinition("NXQL_PP_FIXED_PART");
        Assert.assertNotNull(ppdef);
        HashMap<String, Serializable> props = new HashMap<>();
        DocumentModel model = new DocumentModelImpl("/", "doc", "AdvancedSearch");
        String[] sources = { "Source1", "Source2" };
        model.setProperty("advanced_search", "source_agg", sources);
        props.put(ElasticSearchNativePageProvider.CORE_SESSION_PROPERTY, (Serializable) session);
        long pageSize = 5;
        PageProvider<?> pp = pps.getPageProvider("NXQL_PP_FIXED_PART", ppdef, model, null, pageSize, (long) 0, props);
        // create 10 docs
        startTransaction();
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc = session.createDocument(doc);
        }

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(10);
        startTransaction();

        String[] params = { session.getRootDocument().getId() };
        pp.setParameters(params);

        // get current page
        List<DocumentModel> p = (List<DocumentModel>) pp.getCurrentPage();
        Assert.assertEquals(10, pp.getResultsCount());
        Assert.assertNotNull(p);
        Assert.assertEquals(pageSize, p.size());
        Assert.assertEquals(2, pp.getNumberOfPages());
        DocumentModel doc = p.get(0);
    }

    @Test
    public void testNativePredicateIn() throws Exception {
        QueryBuilder qb;
        PageProviderService pps = Framework.getService(PageProviderService.class);
        WhereClauseDefinition whereClause = pps.getPageProviderDefinition("TEST_IN").getWhereClause();
        DocumentModel model = new DocumentModelImpl("/", "doc", "File");
        model.setPropertyValue("dc:subjects", new String[] { "foo", "bar" });

        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, null, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"terms\" : {\n"
                + "            \"dc:title\" : [ \"foo\", \"bar\" ]\n" + "          }\n" + "        }\n" + "      }\n"
                + "    }\n" + "  }\n" + "}", qb.toString());

        model.setPropertyValue("dc:subjects", new String[] { "foo" });
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, null, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"terms\" : {\n"
                + "            \"dc:title\" : [ \"foo\" ]\n" + "          }\n" + "        }\n" + "      }\n"
                + "    }\n" + "  }\n" + "}", qb.toString());

        // criteria with no values are removed
        model.setPropertyValue("dc:subjects", new String[] {});
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, null, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"match_all\" : { }\n" + "}", qb.toString());
    }

    @Test
    public void testNativePredicateInIntegers() throws Exception {
        QueryBuilder qb;
        PageProviderService pps = Framework.getService(PageProviderService.class);
        WhereClauseDefinition whereClause = pps.getPageProviderDefinition("TEST_IN_INTEGERS").getWhereClause();
        DocumentModel model = new DocumentModelImpl("/", "doc", "AdvancedSearch");
        @SuppressWarnings("boxing")
        Integer[] array1 = new Integer[] { 1, 2, 3 };
        model.setPropertyValue("search:integerlist", array1);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, null, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"terms\" : {\n"
                + "            \"size\" : [ 1, 2, 3 ]\n" + "          }\n" + "        }\n" + "      }\n" + "    }\n"
                + "  }\n" + "}", qb.toString());

        // lists work too
        @SuppressWarnings("boxing")
        List<Long> list = Arrays.asList(1L, 2L, 3L);
        model.setPropertyValue("search:integerlist", (Serializable) list);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, null, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"terms\" : {\n"
                + "            \"size\" : [ 1, 2, 3 ]\n" + "          }\n" + "        }\n" + "      }\n" + "    }\n"
                + "  }\n" + "}", qb.toString());

    }

    @Test
    public void testNativePredicateInStringList() throws Exception {
        QueryBuilder qb;
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);
        WhereClauseDefinition whereClause = pps.getPageProviderDefinition("ADVANCED_SEARCH").getWhereClause();
        String[] params = { "foo" };
        DocumentModel model = new DocumentModelImpl("/", "doc", "AdvancedSearch");
        String[] arrayString = new String[] { "1", "2", "3" };
        model.setPropertyValue("search:subjects", arrayString);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, true);
        String json = qb.toString();
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : [ {\n"
                + "      \"query_string\" : {\n" + "        \"query\" : \"ecm\\\\:parentId: \\\"foo\\\"\"\n"
                + "      }\n" + "    }, {\n" + "      \"constant_score\" : {\n" + "        \"filter\" : {\n"
                + "          \"terms\" : {\n" + "            \"dc:subjects\" : [ \"1\", \"2\", \"3\" ]\n"
                + "          }\n" + "        }\n" + "      }\n" + "    } ]\n" + "  }\n" + "}", qb.toString());

        // lists work too
        @SuppressWarnings("boxing")
        List<String> list = Arrays.asList(arrayString);
        model.setPropertyValue("search:subjects", (Serializable) list);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, true);
        assertEqualsEvenUnderWindows(json, qb.toString());

        // don't take into account empty list
        list = new ArrayList<>();
        model.setPropertyValue("search:subjects", (Serializable) list);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, null, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"match_all\" : { }\n" + "}", qb.toString());
    }

    @Test
    public void testNxqlPredicateInStringList() throws Exception {
        QueryBuilder qb;
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);
        WhereClauseDefinition whereClause = pps.getPageProviderDefinition("ADVANCED_SEARCH_NXQL").getWhereClause();
        DocumentModel model = new DocumentModelImpl("/", "doc", "AdvancedSearch");
        String[] arrayString = new String[] { "1", "2", "3" };
        Object[] params = { "foo", Arrays.asList(arrayString) };
        model.setPropertyValue("search:subjects", arrayString);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, false);
        String json = qb.toString();
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : [ {\n" + "      \"bool\" : {\n"
                + "        \"must\" : [ {\n" + "          \"constant_score\" : {\n" + "            \"filter\" : {\n"
                + "              \"term\" : {\n" + "                \"ecm:parentId\" : \"foo\"\n" + "              }\n"
                + "            }\n" + "          }\n" + "        }, {\n" + "          \"constant_score\" : {\n"
                + "            \"filter\" : {\n" + "              \"terms\" : {\n"
                + "                \"dc:subject\" : [ \"1\", \"2\", \"3\" ]\n" + "              }\n"
                + "            }\n" + "          }\n" + "        } ]\n" + "      }\n" + "    }, {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"terms\" : {\n"
                + "            \"dc:subjects\" : [ \"1\", \"2\", \"3\" ]\n" + "          }\n" + "        }\n"
                + "      }\n" + "    } ]\n" + "  }\n" + "}", qb.toString());
    }

    @Test
    public void testNativePredicateIsNull() throws Exception {
        QueryBuilder qb;
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);

        WhereClauseDefinition whereClause = pps.getPageProviderDefinition("ADVANCED_SEARCH").getWhereClause();
        String[] params = { "foo" };
        DocumentModel model = new DocumentModelImpl("/", "doc", "AdvancedSearch");
        model.setPropertyValue("search:title", "bar");

        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : [ {\n"
                + "      \"query_string\" : {\n" + "        \"query\" : \"ecm\\\\:parentId: \\\"foo\\\"\"\n"
                + "      }\n" + "    }, {\n" + "      \"wildcard\" : {\n" + "        \"dc:title\" : {\n"
                + "          \"wildcard\" : \"bar\"\n" + "        }\n" + "      }\n" + "    } ]\n" + "  }\n" + "}",
                qb.toString());

        model.setPropertyValue("search:isPresent", Boolean.TRUE);

        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : [ {\n"
                + "      \"query_string\" : {\n" + "        \"query\" : \"ecm\\\\:parentId: \\\"foo\\\"\"\n"
                + "      }\n" + "    }, {\n" + "      \"wildcard\" : {\n" + "        \"dc:title\" : {\n"
                + "          \"wildcard\" : \"bar\"\n" + "        }\n" + "      }\n" + "    }, {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"missing\" : {\n"
                + "            \"field\" : \"dc:modified\",\n" + "            \"null_value\" : true\n"
                + "          }\n" + "        }\n" + "      }\n" + "    } ]\n" + "  }\n" + "}", qb.toString());

        // only boolean available in schema without default value
        model.setPropertyValue("search:isPresent", Boolean.FALSE);
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : [ {\n"
                + "      \"query_string\" : {\n" + "        \"query\" : \"ecm\\\\:parentId: \\\"foo\\\"\"\n"
                + "      }\n" + "    }, {\n" + "      \"wildcard\" : {\n" + "        \"dc:title\" : {\n"
                + "          \"wildcard\" : \"bar\"\n" + "        }\n" + "      }\n" + "    }, {\n"
                + "      \"constant_score\" : {\n" + "        \"filter\" : {\n" + "          \"missing\" : {\n"
                + "            \"field\" : \"dc:modified\",\n" + "            \"null_value\" : true\n"
                + "          }\n" + "        }\n" + "      }\n" + "    } ]\n" + "  }\n" + "}", qb.toString());

        qb = PageProviderQueryBuilder.makeQuery("SELECT * FROM ? WHERE ? = '?'", new Object[] { "Document", "dc:title",
                null }, false, true, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"query_string\" : {\n"
                + "    \"query\" : \"SELECT * FROM Document WHERE dc:title = ''\"\n" + "  }\n" + "}", qb.toString());

    }

    @Test
    public void testNativeFulltext() throws Exception {
        QueryBuilder qb;
        PageProviderService pps = Framework.getService(PageProviderService.class);
        Assert.assertNotNull(pps);

        WhereClauseDefinition whereClause = pps.getPageProviderDefinition("ADVANCED_SEARCH").getWhereClause();
        String[] params = { "foo" };
        DocumentModel model = new DocumentModelImpl("/", "doc", "AdvancedSearch");
        model.setPropertyValue("search:fulltext_all", "you know for search");
        qb = PageProviderQueryBuilder.makeQuery(model, whereClause, params, true);
        assertEqualsEvenUnderWindows("{\n" + "  \"bool\" : {\n" + "    \"must\" : [ {\n"
                + "      \"query_string\" : {\n" + "        \"query\" : \"ecm\\\\:parentId: \\\"foo\\\"\"\n"
                + "      }\n" + "    }, {\n" + "      \"simple_query_string\" : {\n"
                + "        \"query\" : \"you know for search\",\n" + "        \"fields\" : [ \"_all\" ],\n"
                + "        \"analyzer\" : \"fulltext\",\n" + "        \"default_operator\" : \"and\"\n" + "      }\n"
                + "    } ]\n" + "  }\n" + "}", qb.toString());
    }

    protected void assertEqualsEvenUnderWindows(String expected, String actual) {
        if (SystemUtils.IS_OS_WINDOWS) {
            // make tests pass under Windows
            expected = expected.trim();
            expected = expected.replace("\n", "");
            expected = expected.replace("\r", "");
            actual = actual.trim();
            actual = actual.replace("\n", "");
            actual = actual.replace("\r", "");
        }
        Assert.assertEquals(expected, actual);
    }

}
