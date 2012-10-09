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

package org.zanata.webtrans.client.presenter;

import static org.zanata.webtrans.client.events.NotificationEvent.Severity.Error;

import java.util.List;

import net.customware.gwt.presenter.client.EventBus;
import net.customware.gwt.presenter.client.widget.WidgetPresenter;

import org.zanata.common.ContentState;
import org.zanata.webtrans.client.events.FilterViewEvent;
import org.zanata.webtrans.client.events.FilterViewEventHandler;
import org.zanata.webtrans.client.events.LoadingEvent;
import org.zanata.webtrans.client.events.LoadingEventHandler;
import org.zanata.webtrans.client.events.NotificationEvent;
import org.zanata.webtrans.client.events.TableRowSelectedEvent;
import org.zanata.webtrans.client.events.TableRowSelectedEventHandler;
import org.zanata.webtrans.client.events.TransUnitSaveEvent;
import org.zanata.webtrans.client.events.TransUnitSelectionEvent;
import org.zanata.webtrans.client.events.TransUnitSelectionHandler;
import org.zanata.webtrans.client.resources.WebTransMessages;
import org.zanata.webtrans.client.service.NavigationService;
import org.zanata.webtrans.client.service.TransUnitSaveService;
import org.zanata.webtrans.client.service.TranslatorInteractionService;
import org.zanata.webtrans.client.ui.FilterViewConfirmationDisplay;
import org.zanata.webtrans.client.view.TargetContentsDisplay;
import org.zanata.webtrans.client.view.TransUnitsTableDisplay;
import org.zanata.webtrans.shared.auth.EditorClientId;
import org.zanata.webtrans.shared.model.TransUnit;
import org.zanata.webtrans.shared.model.TransUnitId;
import org.zanata.webtrans.shared.rpc.TransUnitUpdated;

import com.allen_sauer.gwt.log.client.Log;
import com.google.common.base.Objects;
import com.google.gwt.core.client.GWT;
import com.google.inject.Inject;

/**
 * @author Patrick Huang <a
 *         href="mailto:pahuang@redhat.com">pahuang@redhat.com</a>
 */
// @formatter:off
public class TransUnitsTablePresenter extends WidgetPresenter<TransUnitsTableDisplay> implements
      TransUnitSelectionHandler,
      FilterViewEventHandler,
      FilterViewConfirmationDisplay.Listener,
      NavigationService.PageDataChangeListener,
      TransUnitsTableDisplay.Listener,
      TableRowSelectedEventHandler,
      LoadingEventHandler
// @formatter:on
{

   private final TransUnitsTableDisplay display;
   private final WebTransMessages messages;
   private final EventBus eventBus;
   private final NavigationService navigationService;
   private final SourceContentsPresenter sourceContentsPresenter;
   private final TargetContentsPresenter targetContentsPresenter;
   private final TranslatorInteractionService translatorService;

   // state we need to keep track of
   private FilterViewEvent filterOptions = FilterViewEvent.DEFAULT;
   private TransUnitId selectedId;

   @Inject
   // @formatter:off
   public TransUnitsTablePresenter(TransUnitsTableDisplay display, EventBus eventBus, NavigationService navigationService,
                                   SourceContentsPresenter sourceContentsPresenter,
                                   TargetContentsPresenter targetContentsPresenter,
                                   TranslatorInteractionService translatorService,
                                   TransUnitSaveService transUnitSaveService, WebTransMessages messages)
   // @formatter:on
   {
      super(display, eventBus);
      this.display = display;
      this.messages = messages;
      this.display.setRowSelectionListener(this);

      this.display.addFilterConfirmationHandler(this);
      this.eventBus = eventBus;
      this.navigationService = navigationService;
      navigationService.addPageDataChangeListener(this);
      this.sourceContentsPresenter = sourceContentsPresenter;
      this.targetContentsPresenter = targetContentsPresenter;
      this.translatorService = translatorService;

      // we register it here because we can't use eager singleton on it (it
      // references TargetContentsPresenter). And if it's not eagerly created,
      // it won't get created at all!!
      eventBus.addHandler(TransUnitSaveEvent.TYPE, transUnitSaveService);
   }

   @Override
   protected void onBind()
   {
      registerHandler(eventBus.addHandler(FilterViewEvent.getType(), this));
      registerHandler(eventBus.addHandler(TransUnitSelectionEvent.getType(), this));
      registerHandler(eventBus.addHandler(TableRowSelectedEvent.TYPE, this));
      registerHandler(eventBus.addHandler(LoadingEvent.TYPE, this));
   }

   @Override
   protected void onUnbind()
   {
   }

   @Override
   protected void onRevealDisplay()
   {
   }

   @Override
   public void onTransUnitSelected(TransUnitSelectionEvent event)
   {
      TransUnit selection = event.getSelection();
      selectedId = selection.getId();
      Log.debug("selected id: " + selectedId);
      sourceContentsPresenter.setSelectedSource(selectedId);
      targetContentsPresenter.showEditors(selectedId);
      translatorService.transUnitSelected(selection);
   }

   public void goToPage(int pageNumber)
   {
      targetContentsPresenter.savePendingChangesIfApplicable();
      navigationService.gotoPage(pageNumber - 1);
   }

   @Override
   public void onFilterView(FilterViewEvent event)
   {
      filterOptions = event;
      if (!event.isCancelFilter())
      {
         if (targetContentsPresenter.currentEditorContentHasChanged())
         {
            display.showFilterConfirmation();
         }
         else
         {
            hideFilterConfirmationAndDoFiltering();
         }
      }
   }

   private void hideFilterConfirmationAndDoFiltering()
   {
      display.hideFilterConfirmation();
      navigationService.execute(filterOptions);
   }

   @Override
   public void saveChangesAndFilter()
   {
      saveAndFilter(ContentState.Approved);
   }

   @Override
   public void saveAsFuzzyAndFilter()
   {
      saveAndFilter(ContentState.NeedReview);
   }

   private void saveAndFilter(ContentState status)
   {
      if (targetContentsPresenter.getCurrentTransUnitIdOrNull() == null)
      {
         return;
      }
      targetContentsPresenter.saveCurrent(status);
      hideFilterConfirmationAndDoFiltering();
   }

   @Override
   public void discardChangesAndFilter()
   {
      targetContentsPresenter.onCancel(targetContentsPresenter.getCurrentTransUnitIdOrNull());
      hideFilterConfirmationAndDoFiltering();
   }

   @Override
   public void cancelFilter()
   {
      eventBus.fireEvent(new FilterViewEvent(filterOptions.isFilterTranslated(), filterOptions.isFilterNeedReview(), filterOptions.isFilterUntranslated(), true));
      display.hideFilterConfirmation();
   }

   @Override
   public void showDataForCurrentPage(List<TransUnit> transUnits)
   {
      sourceContentsPresenter.showData(transUnits);
      targetContentsPresenter.showData(transUnits);
      display.buildTable(sourceContentsPresenter.getDisplays(), targetContentsPresenter.getDisplays());
   }

   @Override
   public void refreshRow(TransUnit updatedTransUnit, EditorClientId editorClientId, TransUnitUpdated.UpdateType updateType)
   {
      boolean setFocus = false;
      if (Objects.equal(selectedId, updatedTransUnit.getId()))
      {
         if (!Objects.equal(editorClientId, translatorService.getCurrentEditorClientId()))
         {
            // updatedTU is our active row but done by another user
            Log.info("detect concurrent edit. reset editor value");
            eventBus.fireEvent(new NotificationEvent(Error, messages.concurrentEdit()));
         }
         else if (updateType == TransUnitUpdated.UpdateType.WebEditorSaveFuzzy)
         {
            // same user and update type is save fuzzy
            setFocus = true;
         }
      }
      targetContentsPresenter.updateRow(updatedTransUnit);
      if (setFocus)
      {
         targetContentsPresenter.setFocus();
      }
   }

   @Override
   public void highlightSearch(String findMessage)
   {
      sourceContentsPresenter.highlightSearch(findMessage);
      targetContentsPresenter.highlightSearch(findMessage);
   }

   @Override
   public void refreshView()
   {
      for (TargetContentsDisplay targetContentsDisplay : targetContentsPresenter.getDisplays())
      {
         targetContentsDisplay.refresh();
      }
   }

   @Override
   public void onRowSelected(int rowIndexOnPage)
   {
      if (navigationService.getCurrentRowIndexOnPage() != rowIndexOnPage)
      {
         Log.info("current row:" + navigationService.getCurrentRowIndexOnPage() + " rowSelected:" + rowIndexOnPage);
         targetContentsPresenter.savePendingChangesIfApplicable();
         navigationService.selectByRowIndex(rowIndexOnPage);
         display.applySelectedStyle(rowIndexOnPage);
      }
   }

   public void startEditing()
   {
      if (selectedId != null)
      {
         targetContentsPresenter.setFocus();
      }
      else
      {
         // select first row
         onRowSelected(0);
      }
   }

   @Override
   public void onTableRowSelected(TableRowSelectedEvent event)
   {
      TransUnitId selectedId = event.getSelectedId();
      int rowIndex = navigationService.findRowIndexById(selectedId);
      if (rowIndex != NavigationService.UNSELECTED)
      {
         onRowSelected(rowIndex);
      }
   }

   @Override
   public void onLoading(LoadingEvent event)
   {
      if (event == LoadingEvent.START_EVENT)
      {
         display.showLoading();
      }
      else if (event == LoadingEvent.FINISH_EVENT)
      {
         display.hideLoading();
      }
   }

   /**
    * For testing only. Will not work in GWT compiled mode.
    * 
    * @param selectedId current selected id
    */
   protected void setStateForTesting(TransUnitId selectedId)
   {
      if (!GWT.isClient())
      {
         this.selectedId = selectedId;
      }
   }
}