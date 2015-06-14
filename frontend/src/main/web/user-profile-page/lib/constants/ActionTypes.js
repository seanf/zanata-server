var keymirror = require( 'keymirror');

var ActionTypes = keymirror({
  DATE_RANGE_UPDATE: null,
  CONTENT_STATE_UPDATE: null,
  DAY_SELECTED: null
});

module.exports = ActionTypes;
