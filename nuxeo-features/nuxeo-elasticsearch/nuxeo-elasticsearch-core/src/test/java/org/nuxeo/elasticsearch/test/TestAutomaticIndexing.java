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
 *     Thierry Delprat
 *     Benoit Delbosc
 */

package org.nuxeo.elasticsearch.test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.impl.blob.StringBlob;
import org.nuxeo.ecm.core.storage.sql.DatabaseH2;
import org.nuxeo.ecm.core.storage.sql.DatabaseHelper;
import org.nuxeo.ecm.core.trash.TrashService;
import org.nuxeo.ecm.core.work.api.WorkManager;
import org.nuxeo.ecm.platform.tag.TagService;
import org.nuxeo.elasticsearch.api.ElasticSearchAdmin;
import org.nuxeo.elasticsearch.api.ElasticSearchService;
import org.nuxeo.elasticsearch.listener.ElasticSearchInlineListener;
import org.nuxeo.elasticsearch.query.NxQueryBuilder;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * Test "on the fly" indexing via the listener system
 */
@RunWith(FeaturesRunner.class)
@Features({ RepositoryElasticSearchFeature.class })
@Deploy({ "org.nuxeo.ecm.platform.tag" })
@LocalDeploy("org.nuxeo.elasticsearch.core:elasticsearch-test-contrib.xml")
public class TestAutomaticIndexing {

    private static final String IDX_NAME = "nxutest";

    private static final String TYPE_NAME = "doc";

    @Inject
    protected CoreSession session;

    @Inject
    protected ElasticSearchService ess;

    @Inject
    protected TrashService trashService;

    @Inject
    ElasticSearchAdmin esa;

    @Inject
    protected TagService tagService;

    @Inject
    protected WorkManager workManager;

    private boolean syncMode = false;

    private Priority consoleThresold;

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

    public void activateSynchronousMode() throws Exception {
        ElasticSearchInlineListener.useSyncIndexing.set(true);
        syncMode = true;
    }

    @After
    public void restoreAsyncAndConsoleLog() {
        ElasticSearchInlineListener.useSyncIndexing.set(false);
        syncMode = false;
        restoreConsoleLog();
    }

    protected void startTransaction() {
        if (syncMode) {
            ElasticSearchInlineListener.useSyncIndexing.set(true);
        }
        if (!TransactionHelper.isTransactionActive()) {
            TransactionHelper.startTransaction();
        }
        Assert.assertEquals(0, esa.getPendingWorkerCount());
        Assert.assertEquals(0, esa.getPendingCommandCount());
        commandProcessed = esa.getTotalCommandProcessed();
    }

    protected void hideWarningFromConsoleLog() {
        Logger rootLogger = Logger.getRootLogger();
        ConsoleAppender consoleAppender = (ConsoleAppender) rootLogger.getAppender("CONSOLE");
        consoleThresold = consoleAppender.getThreshold();
        consoleAppender.setThreshold(Level.ERROR);
    }

    protected void restoreConsoleLog() {
        if (consoleThresold == null) {
            return;
        }
        Logger rootLogger = Logger.getRootLogger();
        ConsoleAppender consoleAppender = (ConsoleAppender) rootLogger.getAppender("CONSOLE");
        consoleAppender.setThreshold(consoleThresold);
        consoleThresold = null;
    }

    @Before
    public void setupIndex() throws Exception {
        esa.initIndexes(true);
    }

    @Test
    public void shouldIndexDocument() throws Exception {
        startTransaction();
        // create 10 docs
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc = session.createDocument(doc);
        }
        // merge 5
        for (int i = 0; i < 5; i++) {
            DocumentModel doc = session.getDocument(new PathRef("/testDoc" + i));
            doc.setPropertyValue("dc:description", "Description TestMe" + i);
            doc = session.saveDocument(doc);
        }
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(10);

        startTransaction();
        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(10, searchResponse.getHits().getTotalHits());
    }

    @Test
    public void shouldNotIndexDocumentBecauseOfRollback() throws Exception {
        startTransaction();
        // create 10 docs
        activateSynchronousMode();
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc = session.createDocument(doc);
        }
        // Save session to prevent NXP-14494
        session.save();
        TransactionHelper.setTransactionRollbackOnly();
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(0);

        startTransaction();
        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(0, searchResponse.getHits().getTotalHits());
        Assert.assertFalse(esa.isIndexingInProgress());
    }

    @Test
    public void shouldUnIndexDocument() throws Exception {
        startTransaction();
        DocumentModel doc = session.createDocumentModel("/", "testDoc", "File");
        doc.setPropertyValue("dc:title", "TestMe");
        doc = session.createDocument(doc);

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(1, searchResponse.getHits().getTotalHits());

        // now delete the document
        session.removeDocument(doc.getRef());
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(0, searchResponse.getHits().getTotalHits());
    }

    @Test
    public void shouldReIndexDocument() throws Exception {
        startTransaction();
        // create 10 docs
        for (int i = 0; i < 10; i++) {
            DocumentModel doc = session.createDocumentModel("/", "testDoc" + i, "File");
            doc.setPropertyValue("dc:title", "TestMe" + i);
            doc.setPropertyValue("dc:nature", "A");
            doc = session.createDocument(doc);

        }
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(10);

        startTransaction();
        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setQuery(QueryBuilders.matchQuery("dc:nature", "A")).setFrom(0).setSize(
                60).execute().actionGet();
        Assert.assertEquals(10, searchResponse.getHits().getTotalHits());

        int i = 0;
        for (SearchHit hit : searchResponse.getHits()) {
            i++;
            if (i > 8) {
                break;
            }
            DocumentModel doc = session.getDocument(new IdRef(hit.getId()));
            doc.setPropertyValue("dc:nature", "B");
            session.saveDocument(doc);
        }

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(8);

        startTransaction();
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setQuery(QueryBuilders.matchQuery("dc:nature", "A")).setFrom(0).setSize(
                60).execute().actionGet();
        Assert.assertEquals(2, searchResponse.getHits().getTotalHits());

        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setQuery(QueryBuilders.matchQuery("dc:nature", "B")).setFrom(0).setSize(
                60).execute().actionGet();
        Assert.assertEquals(8, searchResponse.getHits().getTotalHits());
    }

    @Test
    public void shouldIndexBinaryFulltext() throws Exception {
        startTransaction();
        activateSynchronousMode(); // this is to prevent race condition that happen NXP-16169
        DocumentModel doc = session.createDocumentModel("/", "myFile", "File");
        BlobHolder holder = doc.getAdapter(BlobHolder.class);
        holder.setBlob(new StringBlob("You know for search"));
        doc = session.createDocument(doc);
        session.save();

        TransactionHelper.commitOrRollbackTransaction();
        // we need to wait for the async fulltext indexing
        WorkManager wm = Framework.getLocalService(WorkManager.class);
        waitForCompletion();

        startTransaction();
        DocumentModelList ret = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document"));
        Assert.assertEquals(1, ret.totalSize());

        ret = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document WHERE ecm:fulltext='search'"));
        Assert.assertEquals(1, ret.totalSize());
    }

    @Test
    public void shouldIndexLargeBinaryFulltext() throws Exception {
        startTransaction();
        activateSynchronousMode(); // this is to prevent race condition that happen NXP-16169
        DocumentModel doc = session.createDocumentModel("/", "myFile", "File");
        BlobHolder holder = doc.getAdapter(BlobHolder.class);
        holder.setBlob(new StringBlob(new String(new char[33000]).replace('\0', 'a') + " search"));
        // Note that token > 32k fails only when using a disk storage elastic configuration
        doc = session.createDocument(doc);
        session.save();

        TransactionHelper.commitOrRollbackTransaction();
        WorkManager wm = Framework.getLocalService(WorkManager.class);
        waitForCompletion();

        startTransaction();
        DocumentModelList ret = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document WHERE ecm:fulltext='search'"));
        Assert.assertEquals(1, ret.totalSize());
    }

    @Test
    public void shouldIndexLargeToken() throws Exception {
        if (!(DatabaseHelper.DATABASE instanceof DatabaseH2)) {
            // db backend need to support field bigger than 32k
            return;
        }
        startTransaction();
        DocumentModel doc = session.createDocumentModel("/", "myFile", "File");
        doc.setPropertyValue("dc:source", "search foo" + new String(new char[33000]).replace('\0', 'a'));
        // Note that token > 32k error is raised only when using a disk storage elastic configuration
        doc = session.createDocument(doc);
        session.save();

        TransactionHelper.commitOrRollbackTransaction();
        WorkManager wm = Framework.getLocalService(WorkManager.class);
        waitForCompletion();

        startTransaction();
        DocumentModelList ret = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document WHERE dc:source STARTSWITH 'search'"));
        Assert.assertEquals(1, ret.totalSize());
    }

    @Test
    public void shouldIndexOnPublishing() throws Exception {
        startTransaction();
        DocumentModel folder = session.createDocumentModel("/", "folder", "Folder");
        folder = session.createDocument(folder);
        DocumentModel doc = session.createDocumentModel("/", "file", "File");
        doc = session.createDocument(doc);
        // publish
        DocumentModel proxy = session.publishDocument(doc, folder);

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(4);

        startTransaction();
        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        // folder, version, file and proxy
        Assert.assertEquals(4, searchResponse.getHits().getTotalHits());

        // unpublish
        session.removeDocument(proxy.getRef());
        DocumentModelList docs = ess.query(new NxQueryBuilder(session) // .fetchFromElasticsearch()
        .nxql("SELECT * FROM Document"));
        Assert.assertEquals(4, docs.totalSize());
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(3, searchResponse.getHits().getTotalHits());

    }

    @Test
    public void shouldUnIndexUsingTrashService() throws Exception {
        startTransaction();
        DocumentModel folder = session.createDocumentModel("/", "folder", "Folder");
        folder = session.createDocument(folder);
        DocumentModel doc = session.createDocumentModel("/", "file", "File");
        doc = session.createDocument(doc);

        trashService.trashDocuments(Arrays.asList(doc));

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(2);

        startTransaction();
        DocumentModelList ret = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document WHERE ecm:currentLifeCycleState != 'deleted'"));
        Assert.assertEquals(1, ret.totalSize());
        trashService.undeleteDocuments(Arrays.asList(doc));

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        ret = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document WHERE ecm:currentLifeCycleState != 'deleted'"));
        Assert.assertEquals(2, ret.totalSize());

        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(2, searchResponse.getHits().getTotalHits());

        trashService.purgeDocuments(session, Collections.singletonList(doc.getRef()));

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(1, searchResponse.getHits().getTotalHits());
    }

    @Test
    public void shouldIndexOnCopy() throws Exception {
        startTransaction();
        DocumentModel folder = session.createDocumentModel("/", "folder", "Folder");
        folder = session.createDocument(folder);
        DocumentModel doc = session.createDocumentModel("/", "file", "File");
        doc = session.createDocument(doc);
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(2);

        startTransaction();
        DocumentRef src = doc.getRef();
        DocumentRef dst = new PathRef("/");
        session.copy(src, dst, "file2");
        // turn the sync flag after the action
        ElasticSearchInlineListener.useSyncIndexing.set(true);
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        startTransaction();

        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).execute().actionGet();
        Assert.assertEquals(3, searchResponse.getHits().getTotalHits());
    }

    @Test
    public void shouldIndexTag() throws Exception {
        // ElasticSearchInlineListener.useSyncIndexing.set(true);
        startTransaction();
        DocumentModel doc = session.createDocumentModel("/", "file", "File");
        doc = session.createDocument(doc);
        tagService.tag(session, doc.getId(), "mytag", "Administrator");
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        ElasticSearchInlineListener.useSyncIndexing.set(true);
        assertNumberOfCommandProcessed(3); // doc, tagging relation and tag

        startTransaction();
        SearchResponse searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).setQuery(
                QueryBuilders.termQuery("ecm:tag", "mytag")).execute().actionGet();
        Assert.assertEquals(1, searchResponse.getHits().getTotalHits());

        tagService.tag(session, doc.getId(), "mytagbis", "Administrator");
        session.save();
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(3); // doc, tagging and new tag

        startTransaction();
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).setQuery(
                QueryBuilders.termQuery("ecm:tag", "mytagbis")).execute().actionGet();
        Assert.assertEquals(1, searchResponse.getHits().getTotalHits());

        tagService.untag(session, doc.getId(), "mytag", "Administrator");
        session.save();
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(2); // doc, tagging

        startTransaction();
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).setQuery(
                QueryBuilders.termQuery("ecm:tag", "mytagbis")).execute().actionGet();
        Assert.assertEquals(1, searchResponse.getHits().getTotalHits());
        searchResponse = esa.getClient().prepareSearch(IDX_NAME).setTypes(TYPE_NAME).setSearchType(
                SearchType.DFS_QUERY_THEN_FETCH).setFrom(0).setSize(60).setQuery(
                QueryBuilders.termQuery("ecm:tag", "mytag")).execute().actionGet();
        Assert.assertEquals(0, searchResponse.getHits().getTotalHits());
    }

    @Test
    public void shouldHandleCreateDelete() throws Exception {
        startTransaction();
        DocumentModel folder = session.createDocumentModel("/", "folder", "Folder");
        folder = session.createDocument(folder);
        DocumentModel doc = session.createDocumentModel("/folder", "note", "Note");
        doc = session.createDocument(doc);
        TransactionHelper.commitOrRollbackTransaction();
        // we don't wait for async
        TransactionHelper.startTransaction();
        session.removeDocument(folder.getRef());
        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        startTransaction();
    }

    @Test
    public void shouldHandleUpdateOnTransientDoc() throws Exception {
        startTransaction();
        DocumentModel tmpDoc = session.createDocumentModel("/", "file", "File");
        tmpDoc.setPropertyValue("dc:title", "TestMe");
        DocumentModel doc = session.createDocument(tmpDoc); // Send an ES_INSERT cmd
        session.saveDocument(doc); // Send an ES_UPDATE merged with ES_INSERT

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        // here we manipulate the transient doc with a null docid
        Assert.assertNull(tmpDoc.getId());
        tmpDoc.setPropertyValue("dc:title", "NewTitle");
        hideWarningFromConsoleLog();
        session.saveDocument(tmpDoc);
        restoreConsoleLog();

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        DocumentModelList docs = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document Where dc:title='NewTitle'"));
        Assert.assertEquals(1, docs.totalSize());
    }

    @Test
    public void shouldHandleUpdateOnTransientDocBis() throws Exception {
        startTransaction();
        DocumentModel tmpDoc = session.createDocumentModel("/", "file", "File");
        tmpDoc.setPropertyValue("dc:title", "TestMe");
        DocumentModel doc = session.createDocument(tmpDoc); // Send an ES_INSERT cmd
        hideWarningFromConsoleLog();
        session.saveDocument(doc); // Send an ES_UPDATE merged with ES_INSERT

        tmpDoc.setPropertyValue("dc:title", "NewTitle"); // ES_UPDATE with transient, merged
        session.saveDocument(tmpDoc);
        restoreConsoleLog();

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
        DocumentModelList docs = ess.query(new NxQueryBuilder(session).nxql("SELECT * FROM Document Where dc:title='NewTitle'"));
        Assert.assertEquals(1, docs.totalSize());
    }

    @Test
    public void shouldHandleUpdateBeforeInsertOnTransientDoc() throws Exception {
        startTransaction();
        DocumentModel folder = session.createDocumentModel("/", "section", "Folder");
        session.createDocument(folder);
        hideWarningFromConsoleLog();
        folder = session.saveDocument(folder); // generate a WARN and an UPDATE command
        restoreConsoleLog();

        TransactionHelper.commitOrRollbackTransaction();
        waitForCompletion();
        assertNumberOfCommandProcessed(1);

        startTransaction();
    }

}
