import Dispatcher from 'lib/dispatchers/UserMatrixDispatcher';
import assign from 'object-assign';
import {Promise} from 'es6-promise';
import {EventEmitter} from 'events';
import {ContentStates} from 'lib/constants/Options';
import {DateRanges} from 'lib/constants/Options';
import ActionTypes from 'lib/constants/ActionTypes';
import Configs from 'lib/constants/Configs';
import utilsDate from 'lib/utils/DateHelper';
import Request from 'superagent';
import moment from 'moment';
import _ from 'lodash';

var CHANGE_EVENT = "change";

var _state = {
  matrix: [],
  matrixForAllDays: [],
  wordCountsForEachDayFilteredByContentState: [],
  wordCountsForSelectedDayFilteredByContentState: [],
  dateRangeOption: DateRanges[0],
  selectedDay: null,
  contentStateOption: ContentStates[0],
  dateRange: function(option) {
    return utilsDate.getDateRangeFromOption(option);
  }
};

function loadFromServer() {
  var dateRangeOption = _state['dateRangeOption'],
    dateRange = utilsDate.getDateRangeFromOption(dateRangeOption),
    url = Configs.baseUrl + dateRange.fromDate + '..' + dateRange.toDate;

  _state['dateRange'] = dateRange;
  //console.log('about to load from server: %s', url);
  return new Promise(function(resolve, reject) {
    // we turn off cache because it seems like if server(maybe just node?)
    // returns 304 unmodified, it won't even reach the callback!
    Request.get(url)
      .set("Cache-Control", "no-cache, no-store, must-revalidate")
      .set("Pragma", "no-cache")
      .set("Expires", 0)
      .end((function (res) {
        //console.log('response:' + res.body);
        if (res.error) {
          console.error(url, res.status, res.error.toString());
          reject(Error(res.error.toString()));
        } else {
          resolve(res['body']);
        }
      }));
  });
}

function handleServerResponse(serverResponse) {
  var dateRange = _state['dateRange'],
    wordCountsForEachDay = transformToTotalWordCountsForEachDay(serverResponse, dateRange),
    contentState = _state['contentStateOption'],
    selectedDay = _state['selectedDay'];

  return _state;
}

/**
 *
 * @param listOfMatrices original server response
 * @param {{fromDate: string, toDate: string, dates: string[]}} dateRange see
 *   DateHelper.getDateRangeFromOption(string)
 * @returns {{label: string, date: string, totalApproved: number,
   *   totalTranslated: number, totalNeedsWork: number, totalActivity:
   *   number}[]}
 */
function transformToTotalWordCountsForEachDay(listOfMatrices, dateRange) {
  var datesOfThisPeriod = dateRange['dates'],
    result = [], matrixForMonth = {},
    index = 0;

  datesOfThisPeriod.forEach(function (dateStr) {
    var entry = listOfMatrices[index] || {},
      totalApproved = 0, totalTranslated = 0, totalNeedsWork = 0;

    while (entry['savedDate'] === dateStr) {
      switch (entry['savedState']) {
        case 'Approved':
          totalApproved += entry['wordCount'];
          break;
        case 'Translated':
          totalTranslated += entry['wordCount'];
          break;
        case 'NeedReview':
          totalNeedsWork += entry['wordCount'];
          break;
        default:
          throw new Error('unrecognized state:' + entry['savedState']);
      }
      index++;
      entry = listOfMatrices[index] || {}
    }

    var matrixForDate = {
      date: dateStr,
      totalApproved: totalApproved,
      totalTranslated: totalTranslated,
      totalNeedsWork: totalNeedsWork,
      totalActivity: totalApproved + totalNeedsWork + totalTranslated
    };

    var theMonth = moment(dateStr).format('YYYY-MM');
    matrixForMonth[theMonth] = matrixForMonth[theMonth] || {total:0, approved:0, translated:0,fuzzy:0};
    matrixForMonth[theMonth]['total'] += matrixForDate.totalActivity;
    matrixForMonth[theMonth]['approved'] += matrixForDate.totalApproved;
    matrixForMonth[theMonth]['translated'] += matrixForDate.totalTranslated;
    matrixForMonth[theMonth]['fuzzy'] += matrixForDate.totalNeedsWork;


    result.push(
      matrixForDate);
  });

  console.info("date,total,approved,translated,fuzzy");
  _.forOwn(matrixForMonth, function(value, key) {

    if (value.total > 0) {
      console.info('%s, %d, %d, %d, %d', key, value.total, value.approved, value.translated, value.fuzzy);
    }
  });

  return result;
}

function mapContentStateToFieldName(selectedOption) {
  switch (selectedOption) {
    case 'Total':
      return 'totalActivity';
    case 'Approved':
      return 'totalApproved';
    case 'Translated':
      return 'totalTranslated';
    case 'Needs Work':
      return 'totalNeedsWork';
  }
}

/**
 *
 * @param listOfMatrices this should be the result of
 *   transformToTotalWordCountsForEachDay().
 * @param {string} selectedContentState
 * @returns {{key: string, label: string, wordCount: number}[]}
 */
function mapTotalWordCountByContentState(listOfMatrices, selectedContentState) {
  var wordCountFieldName = mapContentStateToFieldName(selectedContentState);
  return listOfMatrices.map(function (entry) {
    return {
      date: entry['date'],
      wordCount: entry[wordCountFieldName]
    }
  });
}

/**
 *
 * @param listOfMatrices original server response
 * @param {string} selectedContentState
 * @param {string?} selectedDay optional day
 * @return filtered entries in same form as original server response
 */
function filterByContentStateAndDay(listOfMatrices, selectedContentState, selectedDay) {
  var filteredEntries = listOfMatrices,
    predicates = [],
    predicate;

  // we have messy terminologies!
  selectedContentState = (selectedContentState === 'Needs Work' ? 'NeedReview' : selectedContentState);

  if (selectedDay) {
    predicates.push(function (entry) {
      return entry['savedDate'] === selectedDay;
    });
  }
  if (selectedContentState !== 'Total') {
    predicates.push(function(entry) {
      return entry['savedState'] === selectedContentState;
    });
  }
  if (predicates.length > 0) {
    predicate = function(entry) {
      return predicates.every(function(func) {
        return func.call({}, entry);
      });
    };
    filteredEntries = listOfMatrices.filter(predicate);
  }
  return filteredEntries;
}

var UserMatrixStore = assign({}, EventEmitter.prototype, {
  getMatrixState: function() {
    if (_state.matrixForAllDays.length == 0) {
      loadFromServer()
        .then(handleServerResponse)
        .then(function (newState) {
          UserMatrixStore.emitChange();
        })
    }
    return _state;
  }.bind(this),

  emitChange: function() {
    this.emit(CHANGE_EVENT);
  },

  /**
   * @param {function} callback
   */
  addChangeListener: function(callback) {
    this.on(CHANGE_EVENT, callback);
  },

  /**
   * @param {function} callback
   */
  removeChangeListener: function(callback) {
    this.removeListener(CHANGE_EVENT, callback);
  },

  query: function() {
    _state['dateRangeOption'] = 'One Year';
    _state['selectedDay'] = null;
    loadFromServer()
      .then(handleServerResponse)
      .then(function(newState) {
          console.info('=======================');
      })
      .catch(function(err) {
        console.error('something bad happen:' + err.stack);
      });
  }
});

var server = process.argv[2];
var url = server + '/rest/stats/user/';
var usernames = process.argv[3].split(',');
usernames.forEach(function(username) {
  Configs.baseUrl = url + username + '/';
  console.info('===== For user: %s =====', username);
  UserMatrixStore.query();
});

