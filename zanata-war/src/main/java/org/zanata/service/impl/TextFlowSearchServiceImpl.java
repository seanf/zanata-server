/*
 * Copyright 2012, Red Hat, Inc. and individual contributors as indicated by the
 * @author tags. See the copyright.txt file in the distribution for a full
 * listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package org.zanata.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import lombok.extern.slf4j.Slf4j;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryParser.MultiFieldQueryParser;
import org.apache.lucene.queryParser.ParseException;
import org.apache.lucene.queryParser.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MultiPhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.Version;
import org.hibernate.search.FullTextSession;
import org.hibernate.search.jpa.FullTextEntityManager;
import org.hibernate.search.jpa.FullTextQuery;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.zanata.common.ContentState;
import org.zanata.common.LocaleId;
import org.zanata.dao.DocumentDAO;
import org.zanata.dao.ProjectIterationDAO;
import org.zanata.exception.ZanataServiceException;
import org.zanata.hibernate.search.IndexFieldLabels;
import org.zanata.hibernate.search.TextContainerAnalyzerDiscriminator;
import org.zanata.model.HDocument;
import org.zanata.model.HLocale;
import org.zanata.model.HProjectIteration;
import org.zanata.model.HTextFlow;
import org.zanata.model.HTextFlowTarget;
import org.zanata.search.FilterConstraintToQuery;
import org.zanata.search.FilterConstraints;
import org.zanata.service.LocaleService;
import org.zanata.service.TextFlowSearchService;
import org.zanata.webtrans.shared.model.ContentStateGroup;
import org.zanata.webtrans.shared.model.DocumentId;
import org.zanata.webtrans.shared.model.WorkspaceId;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

/**
 * @author David Mason, <a
 *         href="mailto:damason@redhat.com">damason@redhat.com</a>
 */
@Name("textFlowSearchServiceImpl")
@Scope(ScopeType.STATELESS)
@Slf4j
public class TextFlowSearchServiceImpl implements TextFlowSearchService {

    @In
    private LocaleService localeServiceImpl;

    @In
    private DocumentDAO documentDAO;

    @In
    private FullTextSession session;

    @Override
    public List<HTextFlow> findTextFlows(WorkspaceId workspace,
            FilterConstraints constraints) {
        return findTextFlowsByDocumentPaths(workspace, null, constraints);
    }

    @Override
    public List<HTextFlow> findTextFlows(WorkspaceId workspace,
            List<String> documents, FilterConstraints constraints) {
        return findTextFlowsByDocumentPaths(workspace, documents, constraints);
    }

    /**
     * @param workspace
     *            workspace
     * @param documentPaths
     *            null or empty to search entire project, otherwise only results
     *            for the given document paths will be returned
     * @param constraints
     *            filter constraints
     * @return list of matching text flows
     */
    private List<HTextFlow> findTextFlowsByDocumentPaths(WorkspaceId workspace,
            List<String> documentPaths, FilterConstraints constraints) {
        LocaleId localeId = workspace.getLocaleId();
        String projectSlug = workspace.getProjectIterationId().getProjectSlug();
        String iterationSlug =
                workspace.getProjectIterationId().getIterationSlug();

        // TODO consider whether to allow null and empty search strings.
        // May want to fork to use a different method to retrieve all targets if
        // empty targets are required.

        // check that locale is valid for the workspace
        HLocale hLocale;
        try {
            hLocale =
                    localeServiceImpl.validateLocaleByProjectIteration(
                            localeId, projectSlug, iterationSlug);
        } catch (ZanataServiceException e) {
            throw new ZanataServiceException("Failed to validate locale", e);
        }

        if (!constraints.isSearchInSource() && !constraints.isSearchInTarget()) {
            // searching nowhere
            return Collections.emptyList();
        }

        // FIXME this looks like it assumes only 3 states and would not work
        // properly for getting
        // e.g. only approved strings while there is a search active.
        ContentStateGroup includedStates = constraints.getIncludedStates();
        if (!includedStates.hasNew() && !includedStates.hasFuzzy()
                && !includedStates.hasTranslated()) {
            // including nothing
            return Collections.emptyList();
        }

        return findTextFlowsWithDatabaseSearch(projectSlug, iterationSlug,
                documentPaths, constraints, hLocale);
    }

    /**
     *
     * @see org.zanata.dao.TextFlowDAO#getTextFlowByDocumentIdWithConstraints(org.zanata.webtrans.shared.model.DocumentId,
     *      org.zanata.model.HLocale, org.zanata.search.FilterConstraints, int,
     *      int)
     */
    private List<HTextFlow> findTextFlowsWithDatabaseSearch(String projectSlug,
            String iterationSlug, List<String> documentPaths,
            FilterConstraints constraints, HLocale hLocale) {
        boolean hasDocumentPaths =
                documentPaths != null && !documentPaths.isEmpty();
        log.debug("document paths: {}", documentPaths);
        List<HDocument> documents;
        if (hasDocumentPaths) {
            // TODO this won't scale. But looks like at the moment documentPaths
            // is sourced from url in
            // org.zanata.webtrans.client.presenter.SearchResultsPresenter.updateViewAndRun
            // so it should be ok.
            documents =
                    documentDAO.getByProjectIterationAndDocIdList(projectSlug,
                            iterationSlug, documentPaths);
        } else {
            documents =
                    documentDAO.getAllByProjectIteration(projectSlug,
                            iterationSlug);
        }
        List<Long> documentIds =
                Lists.transform(documents, HDocumentToId.FUNCTION);

        FilterConstraintToQuery toQuery =
                FilterConstraintToQuery.filterInMultipleDocuments(constraints,
                        documentIds);
        String hql = toQuery.toEntityQuery();
        log.debug("hql for searching: {}", hql);
        org.hibernate.Query query = session.createQuery(hql);
        toQuery.setQueryParameters(query, hLocale);
        query.setComment("TextFlowSearchServiceImpl.findTextFlowsWithDatabaseSearch");
        @SuppressWarnings("unchecked")
        List<HTextFlow> result = query.list();
        if (constraints.isCaseSensitive()) {
            // Query results are post-filtered because the content table uses
            // case-insensitive collation
            // so results will always be case-insensitive at this point.
            // This can be removed if the table or query can be updated to
            // specify a case-sensitive
            // collation.
            result = filterCaseSensitive(result, constraints, hLocale.getId());
        }
        return result;
    }

    /**
     * Filter a list of text flows to include only those that have a case
     * sensitive match of the search string in the contents of interest.
     *
     * @param results
     *            the list to filter
     * @param constraints
     *            describing search term and whether to match in source, target
     *            or both
     * @param localeId
     *            used to look up targets if target content is checked
     * @return filtered list
     */
    private List<HTextFlow> filterCaseSensitive(List<HTextFlow> results,
            FilterConstraints constraints, Long localeId) {
        List<HTextFlow> matchingTextFlows = new ArrayList<HTextFlow>();
        String search = constraints.getSearchString();

        scanning_text_flows: for (HTextFlow tf : results) {
            if (constraints.isSearchInSource()) {
                for (String content : tf.getContents()) {
                    if (content.contains(search)) {
                        matchingTextFlows.add(tf);
                        continue scanning_text_flows;
                    }
                }
            }
            if (constraints.isSearchInTarget()) {
                HTextFlowTarget tft = tf.getTargets().get(localeId);
                if (tft != null) {
                    for (String content : tft.getContents()) {
                        if (content.contains(search)) {
                            matchingTextFlows.add(tf);
                            continue scanning_text_flows;
                        }
                    }
                }
            }
        }

        return matchingTextFlows;
    }

    @Override
    public List<HTextFlow> findTextFlows(WorkspaceId workspace, DocumentId doc,
            FilterConstraints constraints) {
        List<String> documentPaths = new ArrayList<String>(1);
        HDocument document = documentDAO.getById(doc.getId());
        documentPaths.add(document.getDocId());

        return this.findTextFlows(workspace, documentPaths, constraints);
    }

    private enum HDocumentToId implements Function<HDocument, Long> {
        FUNCTION;

        @Override
        public Long apply(HDocument input) {
            return input.getId();
        }
    }
}
