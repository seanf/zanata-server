package org.zanata.webtrans.server.rpc;

import java.util.Date;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.zanata.ZanataTest;
import org.zanata.common.LocaleId;
import org.zanata.dao.TextFlowDAO;
import org.zanata.exception.ZanataServiceException;
import org.zanata.model.HAccount;
import org.zanata.model.HLocale;
import org.zanata.model.HPerson;
import org.zanata.model.HProject;
import org.zanata.model.HProjectIteration;
import org.zanata.model.HTextFlow;
import org.zanata.model.HTextFlowTarget;
import org.zanata.model.TestFixture;
import org.zanata.seam.SeamAutowire;
import org.zanata.security.ZanataIdentity;
import org.zanata.service.LocaleService;
import org.zanata.service.impl.TranslationMemoryServiceImpl;
import org.zanata.webtrans.shared.model.ProjectIterationId;
import org.zanata.webtrans.shared.model.WorkspaceId;
import org.zanata.webtrans.shared.rpc.GetTransMemoryDetailsAction;
import org.zanata.webtrans.shared.rpc.TransMemoryDetailsList;
import com.google.common.collect.Lists;

import net.customware.gwt.dispatch.shared.ActionException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Patrick Huang <a
 *         href="mailto:pahuang@redhat.com">pahuang@redhat.com</a>
 */
public class GetTransMemoryDetailsHandlerTest extends ZanataTest {
    private GetTransMemoryDetailsHandler handler;
    @Mock
    private ZanataIdentity identity;
    @Mock
    private TextFlowDAO textFlowDAO;
    @Mock
    private LocaleService localeServiceImpl;

    private HLocale hLocale;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        // @formatter:off
      handler = SeamAutowire.instance()
            .reset()
            .use("identity", identity)
            .use("textFlowDAO", textFlowDAO)
            .use("localeServiceImpl", localeServiceImpl)
            .useImpl(TranslationMemoryServiceImpl.class)
            .ignoreNonResolvable()
            .autowire(GetTransMemoryDetailsHandler.class);
      // @formatter:on
        hLocale = TestFixture.setId(1L, new HLocale(LocaleId.EN));
    }

    private static void setProjectAndIterationSlug(HTextFlow hTextFlow,
            String projectSlug, String iterationSlug) {
        HProjectIteration projectIteration = new HProjectIteration();
        projectIteration.setSlug(iterationSlug);
        HProject project = new HProject();
        project.setName(projectSlug);
        projectIteration.setProject(project);
        hTextFlow.getDocument().setProjectIteration(projectIteration);
    }

    private static HTextFlowTarget addHTextFlowTarget(HLocale hLocale,
            HTextFlow hTextFlow, String username) {
        HTextFlowTarget hTextFlowTarget =
                new HTextFlowTarget(hTextFlow, hLocale);
        HPerson lastModifiedBy = new HPerson();
        HAccount account = new HAccount();
        account.setUsername(username);
        lastModifiedBy.setAccount(account);
        hTextFlowTarget.setLastModifiedBy(lastModifiedBy);
        hTextFlowTarget.setLastChanged(new Date());
        return hTextFlowTarget;
    }

    @Test
    public void testExecute() throws Exception {
        WorkspaceId workspaceId =
                TestFixture.workspaceId(hLocale.getLocaleId());
        GetTransMemoryDetailsAction action =
                new GetTransMemoryDetailsAction(1L, 2L);
        action.setWorkspaceId(workspaceId);
        ProjectIterationId projectIterationId =
                workspaceId.getProjectIterationId();
        when(
                localeServiceImpl.validateLocaleByProjectIteration(
                        workspaceId.getLocaleId(),
                        projectIterationId.getProjectSlug(),
                        projectIterationId.getIterationSlug())).thenReturn(
                hLocale);
        // text flow 1
        HTextFlow hTextFlow1 = TestFixture.makeApprovedHTextFlow(1L, hLocale);
        setProjectAndIterationSlug(hTextFlow1, "project", "master");
        addHTextFlowTarget(hLocale, hTextFlow1, null);
        // text flow 2
        HTextFlow hTextFlow2 = TestFixture.makeApprovedHTextFlow(2L, hLocale);
        setProjectAndIterationSlug(hTextFlow2, "project 2", "beta");
        addHTextFlowTarget(hLocale, hTextFlow2, null);
        when(textFlowDAO.findByIdList(Lists.newArrayList(1L, 2L))).thenReturn(
                Lists.newArrayList(hTextFlow1, hTextFlow2));

        TransMemoryDetailsList result = handler.execute(action, null);

        verify(identity).checkLoggedIn();
        assertThat(result.getItems(), Matchers.hasSize(2));
    }

    @Test(expected = ActionException.class)
    public void testExecuteWithInvalidLocale() throws Exception {
        WorkspaceId workspaceId = TestFixture.workspaceId();
        GetTransMemoryDetailsAction action = new GetTransMemoryDetailsAction();
        action.setWorkspaceId(workspaceId);
        ProjectIterationId projectIterationId =
                workspaceId.getProjectIterationId();
        when(
                localeServiceImpl.validateLocaleByProjectIteration(
                        workspaceId.getLocaleId(),
                        projectIterationId.getProjectSlug(),
                        projectIterationId.getIterationSlug())).thenThrow(
                new ZanataServiceException("test"));

        handler.execute(action, null);
    }

    @Test
    public void testRollback() throws Exception {
        handler.rollback(null, null, null);
    }
}
