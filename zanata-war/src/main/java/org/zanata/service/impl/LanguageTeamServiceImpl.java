package org.zanata.service.impl;

import java.util.List;

import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.core.Events;
import org.zanata.seam.security.ZanataJpaIdentityStore;
import org.zanata.common.LocaleId;
import org.zanata.dao.LocaleDAO;
import org.zanata.dao.LocaleMemberDAO;
import org.zanata.dao.PersonDAO;
import org.zanata.events.LanguageTeamPermissionChangedEvent;
import org.zanata.exception.ZanataServiceException;
import org.zanata.model.HAccount;
import org.zanata.model.HLocale;
import org.zanata.model.HLocaleMember;
import org.zanata.model.HLocaleMember.HLocaleMemberPk;
import org.zanata.model.HPerson;
import org.zanata.service.LanguageTeamService;
import org.zanata.util.Event;

@Name("languageTeamServiceImpl")
@Scope(ScopeType.STATELESS)
public class LanguageTeamServiceImpl implements LanguageTeamService {
    @In
    private PersonDAO personDAO;

    @In
    private LocaleDAO localeDAO;

    @In
    private LocaleMemberDAO localeMemberDAO;

    @In(required = false, value = ZanataJpaIdentityStore.AUTHENTICATED_USER, scope = ScopeType.SESSION)
    private HAccount authenticatedAccount;

    @In("event")
    private Event<LanguageTeamPermissionChangedEvent>
            languageTeamPermissionChangedEvent;


    public List<HLocale> getLanguageMemberships(String userName) {
        return personDAO.getLanguageMembershipByUsername(userName);
    }

    public void joinOrUpdateRoleInLanguageTeam(String locale, Long personId,
            boolean isTranslator, boolean isReviewer, boolean isCoordinator)
            throws ZanataServiceException {
        LocaleId localeId = new LocaleId(locale);
        HPerson currentPerson = personDAO.findById(personId, false);

        boolean alreadyJoined =
                localeMemberDAO.isLocaleMember(personId, localeId);
        HLocaleMember localeMember;
        LanguageTeamPermissionChangedEvent permissionChangedEvent;

        HPerson authenticatedUser = authenticatedAccount.getPerson();
        if (!alreadyJoined) {
            if (currentPerson.getLanguageMemberships().size() >= MAX_NUMBER_MEMBERSHIP) {
                throw new ZanataServiceException(
                        "You can only be a member of up to "
                                + MAX_NUMBER_MEMBERSHIP
                                + " languages at one time.");
            }
            HLocale lang = localeDAO.findByLocaleId(localeId);
            localeMember =
                    new HLocaleMember(currentPerson, lang, isTranslator,
                            isReviewer, isCoordinator);
            lang.getMembers().add(localeMember);
            permissionChangedEvent =
                    new LanguageTeamPermissionChangedEvent(currentPerson,
                            localeId, authenticatedUser)
                            .joiningTheTeam(isTranslator, isReviewer,
                                    isCoordinator);
        } else {
            localeMember =
                    localeMemberDAO.findByPersonAndLocale(personId, localeId);
            permissionChangedEvent =
                    new LanguageTeamPermissionChangedEvent(currentPerson,
                            localeId, authenticatedUser)
                            .updatingPermissions(localeMember,
                            isTranslator, isReviewer, isCoordinator);
            localeMember.setTranslator(isTranslator);
            localeMember.setReviewer(isReviewer);
            localeMember.setCoordinator(isCoordinator);
        }
        localeMemberDAO.makePersistent(localeMember);
        localeMemberDAO.flush();
        languageTeamPermissionChangedEvent.fire(permissionChangedEvent);
    }

    public boolean leaveLanguageTeam(String locale, Long personId) {
        HLocale lang = localeDAO.findByLocaleId(new LocaleId(locale));
        HPerson currentPerson = personDAO.findById(personId, false);
        final HLocaleMember membership =
                localeMemberDAO.findById(new HLocaleMemberPk(currentPerson,
                        lang), true);

        if (membership != null) {
            localeMemberDAO.makeTransient(membership);
            lang.getMembers().remove(membership);
            localeMemberDAO.flush();
            HPerson doneByPerson = authenticatedAccount.getPerson();
            languageTeamPermissionChangedEvent.fire(
                    new LanguageTeamPermissionChangedEvent(
                            currentPerson, lang.getLocaleId(),
                            doneByPerson)
                            .updatingPermissions(membership, false,
                                    false, false));
            return true;
        }

        return false;
    }

    @Override
    public boolean isUserReviewer(Long personId) {
        return !localeMemberDAO.findByPersonWithReviewerRole(personId)
                .isEmpty();
    }
}
